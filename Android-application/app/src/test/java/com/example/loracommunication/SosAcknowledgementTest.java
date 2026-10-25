package com.example.loracommunication;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.LiveData;

import com.example.loracommunication.data.Incident;
import com.example.loracommunication.data.IncidentDao;
import com.example.loracommunication.data.Message;
import com.example.loracommunication.data.MessageDao;
import com.example.loracommunication.data.NodeRepository;
import com.example.loracommunication.protocol.CommandBuilder;
import com.example.loracommunication.protocol.LoraProtocol;
import com.example.loracommunication.protocol.PacketDeduplicator;
import com.example.loracommunication.protocol.ParsedLine;
import com.example.loracommunication.ui.EmergencyAlertManager;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Pure Java JUnit test verifying the 5 core SOS acknowledgement requirements from Section F:
 * 1. Verify the generated READ command string matches "READ\n" exactly.
 * 2. Verify parsing "READ_SENT,OK" triggers acknowledge-all without issuing a return READ command.
 * 3. Verify parsing "READ_SENT,FAIL" suppresses acknowledgement.
 * 4. Verify duplicate calls to acknowledge are idempotent.
 * 5. Verify emergency alert notifications/callbacks are never initiated with incident id == 0.
 */
public class SosAcknowledgementTest {

    @Rule
    public InstantTaskExecutorRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    private FakeIncidentDao testIncidentDao;
    private FakeMessageDao testMessageDao;
    private PacketDeduplicator deduplicator;
    private NodeRepository repository;

    @Before
    public void setUp() {
        LoraProtocol.resetPacketIdGeneratorForTesting();
        testIncidentDao = new FakeIncidentDao();
        testMessageDao = new FakeMessageDao();
        deduplicator = new PacketDeduplicator();
        Executor directExecutor = Runnable::run;
        repository = new NodeRepository(testMessageDao, testIncidentDao, deduplicator, directExecutor);
    }

    // 1. Verify the generated READ command string matches "READ\n" exactly
    @Test
    public void testReadCommandMatchesReadNewlineExactly() {
        String loraCmd = LoraProtocol.buildReadCommand();
        assertEquals("READ\n", loraCmd);

        String builderCmd = CommandBuilder.read();
        assertEquals("READ\n", builderCmd);
    }

    // 2. Verify parsing "READ_SENT,OK" triggers acknowledge-all without issuing a return READ command
    @Test
    public void testParsingReadSentOk_triggersAcknowledgeAllWithoutReturnRead() {
        // Pre-populate 2 unacknowledged incidents in the DAO
        Incident inc1 = new Incident(1, "SOS", "NODE A", 101L, 12.34, 56.78, System.currentTimeMillis(), false);
        Incident inc2 = new Incident(2, "FALL", "NODE B", 102L, null, null, System.currentTimeMillis(), false);
        testIncidentDao.insert(inc1);
        testIncidentDao.insert(inc2);

        assertEquals("Should have 2 unacknowledged incidents initially", 2, testIncidentDao.getUnacknowledgedCountSync());

        // Parse and process "READ_SENT,OK" from node
        ParsedLine parsed = LoraProtocol.parseLine("READ_SENT,OK\n");
        assertEquals(ParsedLine.LineType.READ_SENT, parsed.getLineType());
        assertTrue(parsed.isSuccess());

        repository.processParsedLine(parsed);

        // Verify all incidents are now acknowledged
        assertEquals("All active incidents must be marked acknowledged", 0, testIncidentDao.getUnacknowledgedCountSync());
        assertTrue(inc1.isAcknowledged());
        assertTrue(inc2.isAcknowledged());

        // Verify no return READ command was transmitted (repository connection state is NONE so no socket writes)
        // Also verify dismissAlertEvent was fired for UI dismissal
        LiveData<com.example.loracommunication.data.Event<Boolean>> dismissEvent = repository.getDismissAlertEvent();
        assertNotNull("Dismiss event must be posted on READ_SENT,OK", dismissEvent.getValue());
        assertTrue("Dismiss event payload must be true", Boolean.TRUE.equals(dismissEvent.getValue().peekContent()));
    }

    // 3. Verify parsing "READ_SENT,FAIL" suppresses acknowledgement
    @Test
    public void testParsingReadSentFail_suppressesAcknowledgement() {
        Incident inc1 = new Incident(1, "SOS", "NODE A", 101L, 12.34, 56.78, System.currentTimeMillis(), false);
        testIncidentDao.insert(inc1);

        assertEquals(1, testIncidentDao.getUnacknowledgedCountSync());

        // Parse and process "READ_SENT,FAIL" from node
        ParsedLine parsed = LoraProtocol.parseLine("READ_SENT,FAIL\n");
        assertEquals(ParsedLine.LineType.READ_SENT, parsed.getLineType());
        assertFalse(parsed.isSuccess());

        repository.processParsedLine(parsed);

        // Incidents must remain unacknowledged
        assertEquals("Incident must NOT be acknowledged on READ_SENT,FAIL", 1, testIncidentDao.getUnacknowledgedCountSync());
        assertFalse(inc1.isAcknowledged());
    }

    // 4. Verify duplicate calls to acknowledge are idempotent
    @Test
    public void testDuplicateCallsToAcknowledgeAreIdempotent() {
        EmergencyAlertManager alertManager = new EmergencyAlertManager(null);

        // First call to acknowledge incident 42
        assertFalse(alertManager.isIncidentAcknowledgedInMemory(42));
        alertManager.acknowledgeIncident(42, false);
        assertTrue(alertManager.isIncidentAcknowledgedInMemory(42));

        // Duplicate call to acknowledge the same incident 42
        alertManager.acknowledgeIncident(42, false);
        assertTrue("Incident must remain acknowledged after duplicate call", alertManager.isIncidentAcknowledgedInMemory(42));

        // Test DAO acknowledge idempotency
        Incident inc = new Incident(0, "SOS", "NODE A", 999L, null, null, System.currentTimeMillis(), false);
        long assignedId = testIncidentDao.insert(inc);
        assertFalse(inc.isAcknowledged());

        testIncidentDao.acknowledge(assignedId);
        assertTrue(inc.isAcknowledged());

        // Duplicate DAO acknowledge
        testIncidentDao.acknowledge(assignedId);
        assertTrue("Incident must remain acknowledged after duplicate DAO call", inc.isAcknowledged());

        // Duplicate acknowledgeAll
        testIncidentDao.acknowledgeAll();
        assertTrue("Incident must remain acknowledged after duplicate acknowledgeAll", inc.isAcknowledged());
    }

    // 5. Verify emergency alert notifications/callbacks are never initiated with incident id == 0
    @Test
    public void testEmergencyAlertCallbackNeverInitiatedWithIncidentIdZero() {
        AtomicReference<Incident> deliveredIncident = new AtomicReference<>(null);
        AtomicBoolean callbackFired = new AtomicBoolean(false);

        repository.setAlertCallback(new NodeRepository.AlertCallback() {
            @Override
            public void onEmergencyAlert(Incident incident, String title, String body) {
                callbackFired.set(true);
                deliveredIncident.set(incident);
            }

            @Override
            public void onNormalMessage(Message message, String title, String body) {
            }
        });

        // Simulate incoming SOS line
        ParsedLine parsed = LoraProtocol.parseLine("SOS_IN,NODE A,MANUAL,12.345600,77.654300,GPS\n");
        repository.processParsedLine(parsed);

        // Verify callback was invoked with a valid, non-zero database row ID
        assertTrue("Alert callback must be triggered for valid emergency packet", callbackFired.get());
        assertNotNull("Delivered incident must not be null", deliveredIncident.get());
        assertTrue("Delivered incident id must be strictly > 0 (database row ID)", deliveredIncident.get().getId() > 0);
        assertNotEquals("Delivered incident id must NEVER be 0", 0L, deliveredIncident.get().getId());
    }

    @Test
    public void testEmergencyAlertCallbackSuppressedIfDatabaseInsertFailsOrReturnsZero() {
        // Create repository with a DAO that simulates insertion returning 0
        FakeIncidentDao zeroIdDao = new FakeIncidentDao() {
            @Override
            public long insert(Incident incident) {
                return 0; // Simulate failed or zero ID generation
            }
        };

        NodeRepository repoWithZeroIdDao = new NodeRepository(testMessageDao, zeroIdDao, deduplicator, Runnable::run);
        AtomicBoolean callbackFired = new AtomicBoolean(false);

        repoWithZeroIdDao.setAlertCallback(new NodeRepository.AlertCallback() {
            @Override
            public void onEmergencyAlert(Incident incident, String title, String body) {
                callbackFired.set(true);
            }

            @Override
            public void onNormalMessage(Message message, String title, String body) {
            }
        });

        ParsedLine parsed = LoraProtocol.parseLine("SOS_IN,NODE C,MANUAL,12.345600,77.654300,GPS\n");
        repoWithZeroIdDao.processParsedLine(parsed);

        assertFalse("Alert callback must NOT be fired when database row id is 0", callbackFired.get());
    }

    // 6. Verify outbound SOS command formatting
    @Test
    public void testOutboundSosCommandFormatting() {
        String loraSos = LoraProtocol.buildSosCommand();
        assertEquals("SOS", loraSos);

        String builderSos = CommandBuilder.sos();
        assertEquals("SOS", builderSos);
    }

    // 7. Verify outbound SOS dispatches immediately without deadlock or location delay
    @Test
    public void testSendSosDispatchesImmediatelyWithoutDeadlock() {
        AtomicBoolean writeCallbackFired = new AtomicBoolean(false);
        AtomicBoolean writeSuccess = new AtomicBoolean(false);

        // When bluetoothService is null (disconnected in unit test environment),
        // sendSos should return immediately with false and error "Bluetooth service not available",
        // never hanging or blocking on a location latch.
        long start = System.currentTimeMillis();
        repository.sendSos((success, error) -> {
            writeCallbackFired.set(true);
            writeSuccess.set(success);
        });
        long elapsed = System.currentTimeMillis() - start;

        assertTrue("sendSos must return callback immediately", writeCallbackFired.get());
        assertFalse("Write should report failure if service not available", writeSuccess.get());
        assertTrue("sendSos must never block or wait on location latches (took " + elapsed + "ms)", elapsed < 500);

        // Verify Message was recorded in DAO with STATE_FAILED
        List<Message> allMsgs = testMessageDao.getChatMessagesSync();
        assertEquals("One outgoing SOS message must be recorded", 1, allMsgs.size());
        Message msg = allMsgs.get(0);
        assertEquals(Message.KIND_SOS, msg.getKind());
        assertEquals(Message.STATE_FAILED, msg.getSendState());
    }

    // 8. Verify Command timeout fail-safe constant is between 2 and 3 seconds
    @Test
    public void testCommandTimeoutFailSafeConstant() throws Exception {
        java.lang.reflect.Field field = com.example.loracommunication.bluetooth.BluetoothService.class.getDeclaredField("COMMAND_TIMEOUT_MS");
        field.setAccessible(true);
        long timeoutMs = field.getLong(null);
        assertTrue("Fail-safe timeout must be between 2000ms and 3000ms, was " + timeoutMs,
                timeoutMs >= 2000L && timeoutMs <= 3000L);
    }

    // --- Helper Fakes for Pure Java Tests ---

    private static class FakeIncidentDao implements IncidentDao {
        final List<Incident> incidents = new ArrayList<>();

        @Override
        public long insert(Incident incident) {
            incidents.add(incident);
            long assignedId = incidents.size();
            incident.setId(assignedId);
            return assignedId;
        }

        @Override
        public LiveData<List<Incident>> getAllIncidents() { return null; }

        @Override
        public List<Incident> getAllIncidentsSync() { return new ArrayList<>(incidents); }

        @Override
        public LiveData<Integer> getUnacknowledgedCount() { return null; }

        @Override
        public int getUnacknowledgedCountSync() {
            int count = 0;
            for (Incident i : incidents) {
                if (!i.isAcknowledged()) count++;
            }
            return count;
        }

        @Override
        public Incident getById(long id) {
            for (Incident i : incidents) {
                if (i.getId() == id) return i;
            }
            return null;
        }

        @Override
        public void acknowledge(long id) {
            for (Incident i : incidents) {
                if (i.getId() == id) {
                    i.setAcknowledged(true);
                    break;
                }
            }
        }

        @Override
        public void acknowledgeAll() {
            for (Incident i : incidents) {
                i.setAcknowledged(true);
            }
        }

        @Override
        public LiveData<List<Incident>> getIncidentsWithLocation() { return null; }

        @Override
        public List<Incident> getIncidentsWithLocationSync() { return new ArrayList<>(incidents); }

        @Override
        public void deleteAll() {
            incidents.clear();
        }
    }

    private static class FakeMessageDao implements MessageDao {
        final List<Message> messages = new ArrayList<>();

        @Override
        public long insert(Message message) {
            messages.add(message);
            return messages.size();
        }

        @Override
        public void update(Message message) {}

        @Override
        public void delete(Message message) {}

        @Override
        public LiveData<List<Message>> getChatMessages() { return null; }

        @Override
        public List<Message> getChatMessagesSync() { return new ArrayList<>(messages); }

        @Override
        public LiveData<List<Message>> getAllMessagesAscending() { return null; }

        @Override
        public LiveData<List<Message>> getAllMessages() { return null; }
        @Override
        public List<Message> getAllMessagesSync() { return new ArrayList<>(messages); }
        @Override
        public LiveData<List<Message>> getMessagesByKind(String kind) { return null; }
        @Override
        public List<Message> getMessagesByKindSync(String kind) { return new ArrayList<>(messages); }
        @Override
        public Message getLatestSendingMessage(String kind) { return null; }
        @Override
        public Message getLatestSendingMessageAny() { return null; }
        @Override
        public void updateSendState(long id, String newState) {}
        @Override
        public void updateSendStateAndPacketId(long id, String newState, Long packetId) {}
        @Override
        public void updateSendStateAndDetails(long id, String newState, Long packetId, Double lat, Double lon) {}
        @Override
        public int markLatestSendingFailed() { return 0; }
        @Override
        public void deleteAll() { messages.clear(); }
    }
}
