package com.example.loracommunication.data;

import androidx.lifecycle.LiveData;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.example.loracommunication.protocol.LoraPacket;
import com.example.loracommunication.protocol.LoraProtocol;
import com.example.loracommunication.protocol.PacketDeduplicator;
import com.example.loracommunication.protocol.ParsedLine;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.Assert.*;

public class DataModelAndDeduplicationTest {

    @Rule
    public InstantTaskExecutorRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    private FakeMessageDao messageDao;
    private FakeIncidentDao incidentDao;
    private PacketDeduplicator deduplicator;
    private NodeRepository repository;

    @Before
    public void setUp() {
        messageDao = new FakeMessageDao();
        incidentDao = new FakeIncidentDao();
        deduplicator = new PacketDeduplicator();
        Executor directExecutor = Runnable::run;
        repository = new NodeRepository(messageDao, incidentDao, deduplicator, directExecutor);
    }

    // --- 1. Room Migration Test ---

    @Test
    public void testMigration_1_to_2_executesExpectedSql() {
        List<String> executedSql = new ArrayList<>();

        SupportSQLiteDatabase fakeDb = (SupportSQLiteDatabase) Proxy.newProxyInstance(
                SupportSQLiteDatabase.class.getClassLoader(),
                new Class<?>[]{SupportSQLiteDatabase.class},
                (proxy, method, args) -> {
                    if ("execSQL".equals(method.getName()) && args != null && args.length > 0) {
                        executedSql.add((String) args[0]);
                    }
                    return null;
                }
        );

        AppDatabase.MIGRATION_1_2.migrate(fakeDb);

        assertEquals("Should execute 6 SQL statements for migration", 6, executedSql.size());

        // Check messages_new table creation
        String createNew = executedSql.get(0);
        assertTrue(createNew.contains("CREATE TABLE IF NOT EXISTS `messages_new`"));
        assertTrue(createNew.contains("`node` TEXT"));
        assertTrue(createNew.contains("`packetId` INTEGER"));
        assertTrue(createNew.contains("`direction` TEXT"));
        assertTrue(createNew.contains("`kind` TEXT"));
        assertTrue(createNew.contains("`sendState` TEXT"));

        // Check data copy and transformations
        String copyData = executedSql.get(1);
        assertTrue(copyData.contains("INSERT INTO `messages_new`"));
        assertTrue(copyData.contains("`senderId` AS `node`"));
        assertTrue(copyData.contains("CASE WHEN `isSent` = 1 THEN 'SENT' ELSE 'RECEIVED' END"));
        assertTrue(copyData.contains("CASE WHEN `isSos` = 1 THEN 'SOS' ELSE 'MSG' END"));
        assertTrue(copyData.contains("CASE WHEN `latitude` = 0.0 AND `longitude` = 0.0 THEN NULL ELSE `latitude` END"));

        // Check table replacement
        assertTrue(executedSql.get(2).contains("DROP TABLE `messages`"));
        assertTrue(executedSql.get(3).contains("ALTER TABLE `messages_new` RENAME TO `messages`"));

        // Check incidents table creation and unique index
        String createIncidents = executedSql.get(4);
        assertTrue(createIncidents.contains("CREATE TABLE IF NOT EXISTS `incidents`"));
        assertTrue(createIncidents.contains("`kind` TEXT"));
        assertTrue(createIncidents.contains("`node` TEXT"));
        assertTrue(createIncidents.contains("`packetId` INTEGER"));
        assertTrue(createIncidents.contains("`acknowledged` INTEGER"));

        String createIndex = executedSql.get(5);
        assertTrue(createIndex.contains("CREATE UNIQUE INDEX IF NOT EXISTS `index_incidents_node_packetId_kind`"));
        assertTrue(createIndex.contains("(`node`, `packetId`, `kind`)"));
    }

    // --- 2. De-duplication Tests ---

    @Test
    public void testPacketDeduplication_detectsDuplicatesAcrossNodeIdType() {
        LoraPacket p1 = new LoraPacket(LoraPacket.Type.MSG, "NODE A", "101", 12.34, 56.78, "Hello");
        LoraPacket p1Duplicate = new LoraPacket(LoraPacket.Type.MSG, "NODE A", "101", 12.34, 56.78, "Hello duplicate");
        LoraPacket p2DifferentNode = new LoraPacket(LoraPacket.Type.MSG, "NODE B", "101", 12.34, 56.78, "Hello from B");
        LoraPacket p3DifferentType = new LoraPacket(LoraPacket.Type.SOS, "NODE A", "101", 12.34, 56.78, null);
        LoraPacket p4DifferentId = new LoraPacket(LoraPacket.Type.MSG, "NODE A", "102", 12.34, 56.78, "New message");

        assertTrue("First packet should be new", deduplicator.trackIfNew(p1));
        assertFalse("Same node, id, and type should be detected as duplicate", deduplicator.trackIfNew(p1Duplicate));
        assertTrue("Different node should be new", deduplicator.trackIfNew(p2DifferentNode));
        assertTrue("Different packet type should be new", deduplicator.trackIfNew(p3DifferentType));
        assertTrue("Different packet id should be new", deduplicator.trackIfNew(p4DifferentId));
    }

    // --- 3. Own TX Never Creates An Incident Tests ---

    @Test
    public void testOwnTx_sosPacket_neverCreatesIncidentAndMarksSendingComplete() {
        // Prepare an outgoing SOS row in SENDING state
        Message sendingSos = new Message(1, "Me", null, Message.DIRECTION_SENT,
                Message.KIND_SOS, "Emergency SOS", System.currentTimeMillis(), null, null, Message.STATE_SENDING);
        messageDao.messages.add(sendingSos);

        // Process TX echo line from local node: TX|SOS|NODE A|42|13.08270|80.27070
        ParsedLine txLine = LoraProtocol.parseLine("TX|SOS|NODE A|42|13.08270|80.27070");
        repository.processParsedLine(txLine);

        // Assert: NO incident was created
        assertEquals("Own TX must NEVER create an incident", 0, incidentDao.incidents.size());

        // Assert: SENDING row transitioned to SENT_OVER_LORA with packet id and coordinates
        assertEquals("Should not add extra rows if SENDING existed", 1, messageDao.messages.size());
        Message updated = messageDao.messages.get(0);
        assertEquals(Message.STATE_SENT_OVER_LORA, updated.getSendState());
        assertEquals(Long.valueOf(42), updated.getPacketId());
        assertEquals(Double.valueOf(13.08270), updated.getLatitude());
        assertEquals(Double.valueOf(80.27070), updated.getLongitude());
    }

    @Test
    public void testOwnTx_fallPacket_neverCreatesIncident() {
        // Node transmits automatic fall SOS triggered locally (e.g. physical node fall)
        ParsedLine txFall = LoraProtocol.parseLine("TX|FALL|NODE A|99|13.08270|80.27070");
        repository.processParsedLine(txFall);

        // Assert: NO incident was created
        assertEquals("Own TX must NEVER create an incident", 0, incidentDao.incidents.size());

        // Assert: Inserted as SENT row
        assertEquals(1, messageDao.messages.size());
        Message sent = messageDao.messages.get(0);
        assertEquals(Message.DIRECTION_SENT, sent.getDirection());
        assertEquals(Message.KIND_FALL, sent.getKind());
        assertEquals(Message.STATE_SENT_OVER_LORA, sent.getSendState());
    }

    @Test
    public void testRxSos_createsIncidentAndHistoryRow() {
        // Remote node sends RX SOS: RX|SOS|NODE B|55|13.12345|80.54321
        ParsedLine rxSos = LoraProtocol.parseLine("RX|SOS|NODE B|55|13.12345|80.54321");
        repository.processParsedLine(rxSos);

        // Assert: Incident is created and unacknowledged
        assertEquals(1, incidentDao.incidents.size());
        Incident incident = incidentDao.incidents.get(0);
        assertEquals("NODE B", incident.getNode());
        assertEquals("SOS", incident.getKind());
        assertEquals(Long.valueOf(55), incident.getPacketId());
        assertFalse(incident.isAcknowledged());
        assertEquals(Double.valueOf(13.12345), incident.getLat());
        assertEquals(Double.valueOf(80.54321), incident.getLon());

        // Assert: Message history row is created
        assertEquals(1, messageDao.messages.size());
        Message msg = messageDao.messages.get(0);
        assertEquals(Message.DIRECTION_RECEIVED, msg.getDirection());
        assertEquals(Message.KIND_SOS, msg.getKind());
        assertEquals("NODE B", msg.getNode());

        // Feed duplicate RX packet: must be dropped
        repository.processParsedLine(rxSos);
        assertEquals("Duplicate RX must be ignored by deduplicator", 1, incidentDao.incidents.size());
        assertEquals("Duplicate RX must not insert additional history row", 1, messageDao.messages.size());
    }

    @Test
    public void testErrorLine_marksLatestSendingFailed() {
        Message sendingMsg = new Message(1, "Me", null, Message.DIRECTION_SENT,
                Message.KIND_MSG, "Hello", System.currentTimeMillis(), null, null, Message.STATE_SENDING);
        messageDao.messages.add(sendingMsg);

        ParsedLine errorLine = LoraProtocol.parseLine("ERROR|COMMAND_TOO_LONG");
        repository.processParsedLine(errorLine);

        assertEquals(Message.STATE_FAILED, sendingMsg.getSendState());
    }

    @Test
    public void testPhoneGpsFallback_statusSourceParsedAndExposed() {
        // Node reports STATUS with SRC=GPS
        repository.simulateLine("STATUS|NODE A|MPU=READY|GPS=FIX|LORA=READY|SRC=GPS");
        NodeStatus status = repository.getNodeStatus().getValue();
        assertNotNull(status);
        assertEquals("GPS", status.getSource());
        assertTrue(status.isGpsSource());
        assertFalse(status.isPhoneSource());

        // Node reports STATUS with SRC=PHONE
        repository.simulateLine("STATUS|NODE A|MPU=READY|GPS=NO_FIX|LORA=READY|SRC=PHONE");
        status = repository.getNodeStatus().getValue();
        assertNotNull(status);
        assertEquals("PHONE", status.getSource());
        assertTrue(status.isPhoneSource());
        assertFalse(status.isGpsSource());

        // Node reports STATUS with SRC=NONE
        repository.simulateLine("STATUS|NODE A|MPU=READY|GPS=NO_FIX|LORA=READY|SRC=NONE");
        status = repository.getNodeStatus().getValue();
        assertNotNull(status);
        assertEquals("NONE", status.getSource());
        assertTrue(status.isNoneSource());
    }

    @Test
    public void testPhoneGpsFallback_locOkUpdatesStateAndStatus() {
        // Initially NO_FIX and NONE source
        repository.simulateLine("STATUS|NODE B|MPU=READY|GPS=NO_FIX|LORA=READY|SRC=NONE");
        assertFalse(repository.nodeHasValidPhoneLoc());

        // Receiving LOC_OK records that node has valid phone coordinates
        repository.simulateLine("LOC_OK|NODE B");
        assertTrue(repository.nodeHasValidPhoneLoc());

        // Status source is updated to PHONE since GPS is NO_FIX
        NodeStatus status = repository.getNodeStatus().getValue();
        assertNotNull(status);
        assertEquals("PHONE", status.getSource());
        assertTrue(status.isPhoneSource());

        // If ERROR|BAD_LOC arrives, nodeHasValidPhoneLoc is invalidated
        repository.simulateLine("ERROR|BAD_LOC");
        assertFalse(repository.nodeHasValidPhoneLoc());

        // Disconnect also clears valid phone location flag
        repository.simulateLine("LOC_OK|NODE B");
        assertTrue(repository.nodeHasValidPhoneLoc());
        repository.setConnectionState(ConnectionState.NONE);
        assertFalse(repository.nodeHasValidPhoneLoc());
    }

    // --- 4. Comma-Based Protocol Adapter Tests ---

    @Test
    public void testMsgIn_withCommas_andTwoIdenticalInARow_bothShown() {
        // Feed first incoming message with commas
        repository.simulateLine("MSG_IN,A,water, food, shelter");

        assertEquals(1, messageDao.messages.size());
        Message msg1 = messageDao.messages.get(0);
        assertEquals("NODE A", msg1.getNode());
        assertEquals("water, food, shelter", msg1.getContent());
        assertEquals(Message.DIRECTION_RECEIVED, msg1.getDirection());
        assertEquals(Message.KIND_MSG, msg1.getKind());
        assertNull(msg1.getLatitude());
        assertNull(msg1.getLongitude());
        assertEquals(Message.STATE_SENT_OVER_LORA, msg1.getSendState());

        // Feed second identical message: MUST NOT be dropped by deduplicator!
        repository.simulateLine("MSG_IN,A,water, food, shelter");
        assertEquals("Both identical MSG_IN messages must be inserted and shown", 2, messageDao.messages.size());
        Message msg2 = messageDao.messages.get(1);
        assertEquals("NODE A", msg2.getNode());
        assertEquals("water, food, shelter", msg2.getContent());
        assertNotEquals(msg1.getPacketId(), msg2.getPacketId());
    }

    @Test
    public void testSosIn_withValidGps_createsIncidentAndHistory() {
        repository.simulateLine("SOS_IN,A,SOS,13.082700,80.270700,GPS");

        assertEquals(1, incidentDao.incidents.size());
        Incident incident = incidentDao.incidents.get(0);
        assertEquals("NODE A", incident.getNode());
        assertEquals("SOS", incident.getKind());
        assertNotNull(incident.getPacketId());
        assertEquals(13.082700, incident.getLat(), 0.000001);
        assertEquals(80.270700, incident.getLon(), 0.000001);
        assertFalse(incident.isAcknowledged());

        assertEquals(1, messageDao.messages.size());
        Message msg = messageDao.messages.get(0);
        assertEquals("NODE A", msg.getNode());
        assertEquals(Message.DIRECTION_RECEIVED, msg.getDirection());
        assertEquals("SOS", msg.getKind());
    }

    @Test
    public void testSosIn_withNaCoordinates_createsIncidentWithNullLocation() {
        // NA coordinates must be treated as INVALID (no 0,0)
        repository.simulateLine("SOS_IN,B,FALL,NA,NA,NONE");

        assertEquals(1, incidentDao.incidents.size());
        Incident incident = incidentDao.incidents.get(0);
        assertEquals("NODE B", incident.getNode());
        assertEquals("FALL", incident.getKind());
        assertNull("NA latitude must result in null", incident.getLat());
        assertNull("NA longitude must result in null", incident.getLon());

        assertEquals(1, messageDao.messages.size());
        Message msg = messageDao.messages.get(0);
        assertEquals("NODE B", msg.getNode());
        assertEquals(Message.KIND_FALL, msg.getKind());
        assertNull(msg.getLatitude());
        assertNull(msg.getLongitude());
    }

    @Test
    public void testMsgSent_ok_marksSendingMessageComplete() {
        Message sending = new Message(1, "Me", null, Message.DIRECTION_SENT,
                Message.KIND_MSG, "Hello", System.currentTimeMillis(), null, null, Message.STATE_SENDING);
        messageDao.messages.add(sending);

        repository.simulateLine("MSG_SENT,OK");

        assertEquals(Message.STATE_SENT_OVER_LORA, sending.getSendState());
    }

    @Test
    public void testMsgSent_fail_marksSendingMessageFailed() {
        Message sending = new Message(1, "Me", null, Message.DIRECTION_SENT,
                Message.KIND_MSG, "Hello", System.currentTimeMillis(), null, null, Message.STATE_SENDING);
        messageDao.messages.add(sending);

        repository.simulateLine("MSG_SENT,FAIL");

        assertEquals(Message.STATE_FAILED, sending.getSendState());
    }

    @Test
    public void testMsgSent_ignoredWhenNoMessageInSending() {
        repository.simulateLine("MSG_SENT,OK");
        assertEquals(0, messageDao.messages.size());
    }

    @Test
    public void testSosSending_insertsOutgoingRow_neverRaisesAlarm() {
        // Physical button pressed on node sends SOS_SENDING
        repository.simulateLine("SOS_SENDING,BUTTON,13.082700,80.270700,GPS");

        // MUST NEVER RAISE AN ALARM OR AN INCOMING INCIDENT
        assertEquals("SOS_SENDING must NEVER create an incident", 0, incidentDao.incidents.size());

        // An outgoing SOS row is inserted in STATE_SENDING
        assertEquals(1, messageDao.messages.size());
        Message msg = messageDao.messages.get(0);
        assertEquals(Message.DIRECTION_SENT, msg.getDirection());
        assertEquals(Message.KIND_SOS, msg.getKind());
        assertEquals(Message.STATE_SENDING, msg.getSendState());
        assertEquals(13.082700, msg.getLatitude(), 0.000001);
        assertEquals(80.270700, msg.getLongitude(), 0.000001);

        // When node confirms transmission: SOS_SENT,OK
        repository.simulateLine("SOS_SENT,OK");
        assertEquals(Message.STATE_SENT_OVER_LORA, msg.getSendState());
    }

    @Test
    public void testSosSending_updatesExistingSendingRow() {
        // App triggered SOS, inserted into STATE_SENDING without location
        Message appSending = new Message(1, "Me", null, Message.DIRECTION_SENT,
                Message.KIND_SOS, "Emergency SOS", System.currentTimeMillis(), null, null, Message.STATE_SENDING);
        messageDao.messages.add(appSending);

        // Node sends SOS_SENDING with location
        repository.simulateLine("SOS_SENDING,APP,13.082700,80.270700,GPS");

        assertEquals("Should not insert new row if one is already SENDING", 1, messageDao.messages.size());
        assertEquals(Message.STATE_SENDING, appSending.getSendState());
        assertEquals(Double.valueOf(13.082700), appSending.getLatitude());
        assertEquals(Double.valueOf(80.270700), appSending.getLongitude());

        // Confirm send
        repository.simulateLine("SOS_SENT,OK");
        assertEquals(Message.STATE_SENT_OVER_LORA, appSending.getSendState());
    }

    @Test
    public void testSosSending_fallReason_createsSendingFallRow() {
        repository.simulateLine("SOS_SENDING,FALL,NA,NA,NONE");

        assertEquals(0, incidentDao.incidents.size());
        assertEquals(1, messageDao.messages.size());
        Message msg = messageDao.messages.get(0);
        assertEquals(Message.DIRECTION_SENT, msg.getDirection());
        assertEquals(Message.KIND_FALL, msg.getKind());
        assertEquals(Message.STATE_SENDING, msg.getSendState());
        assertNull(msg.getLatitude());
        assertNull(msg.getLongitude());

        // Node fails transmission
        repository.simulateLine("SOS_SENT,FAIL");
        assertEquals(Message.STATE_FAILED, msg.getSendState());
    }

    @Test
    public void testErrBusy_marksSendingMessageFailed() {
        Message sending = new Message(1, "Me", null, Message.DIRECTION_SENT,
                Message.KIND_MSG, "Hello", System.currentTimeMillis(), null, null, Message.STATE_SENDING);
        messageDao.messages.add(sending);

        repository.simulateLine("ERR,BUSY");

        assertEquals("BUSY", repository.getLastError().getValue());
        assertEquals(Message.STATE_FAILED, sending.getSendState());
    }

    @Test
    public void testFallWorkflow_start120_sos_cancel() {
        // 1. FALL,START,120
        repository.simulateLine("FALL,START,120");
        FallStateModel fallState = repository.getFallState().getValue();
        assertNotNull(fallState);
        assertTrue(fallState.isMonitoring());
        assertEquals(120, fallState.getDurationSec());

        // 2. FALL,SOS clears monitoring banner
        repository.simulateLine("FALL,SOS");
        fallState = repository.getFallState().getValue();
        assertNotNull(fallState);
        assertFalse(fallState.isMonitoring());
        assertTrue(fallState.isNormal());

        // 3. FALL,CANCEL
        repository.simulateLine("FALL,CANCEL");
        fallState = repository.getFallState().getValue();
        assertNotNull(fallState);
        assertTrue(fallState.isCancelled());
    }

    @Test
    public void testStatusCommaFormat_parsesCorrectly() {
        repository.simulateLine("STATUS,A,GPS,13.082700,80.270700,0,0");
        NodeStatus status = repository.getNodeStatus().getValue();
        assertNotNull(status);
        assertEquals("NODE A", repository.getDeviceName().getValue());
        assertTrue(status.hasGpsFix());
        assertTrue(status.isLoraReady());
        assertTrue(status.isMpuReady());
        assertEquals("GPS", status.getSource());

        repository.simulateLine("STATUS,A,NONE,NA,NA,0,0");
        status = repository.getNodeStatus().getValue();
        assertNotNull(status);
        assertFalse(status.hasGpsFix());
        assertEquals("NONE", status.getSource());
    }

    @Test
    public void testUnknownLine_doesNotCrash() {
        repository.simulateLine("SOME_UNKNOWN_FIRMWARE_DEBUG_LINE,1,2,3");
        // No crash, lastHeardTime updated
        assertNotNull(repository.getLastHeardTime().getValue());
        assertTrue(repository.getLastHeardTime().getValue() > 0);
    }

    // --- Fake Test DAOs ---

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
        public void delete(Message message) {
            messages.remove(message);
        }

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
        public Message getLatestSendingMessage(String kind) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                Message m = messages.get(i);
                if (kind.equalsIgnoreCase(m.getKind()) && Message.STATE_SENDING.equalsIgnoreCase(m.getSendState())) {
                    return m;
                }
            }
            return null;
        }

        @Override
        public Message getLatestSendingMessageAny() {
            for (int i = messages.size() - 1; i >= 0; i--) {
                Message m = messages.get(i);
                if (Message.STATE_SENDING.equalsIgnoreCase(m.getSendState())) {
                    return m;
                }
            }
            return null;
        }

        @Override
        public void updateSendState(long id, String newState) {
            for (Message m : messages) {
                if (m.getId() == id) {
                    m.setSendState(newState);
                    break;
                }
            }
        }

        @Override
        public void updateSendStateAndPacketId(long id, String newState, Long packetId) {
            for (Message m : messages) {
                if (m.getId() == id) {
                    m.setSendState(newState);
                    m.setPacketId(packetId);
                    break;
                }
            }
        }

        @Override
        public void updateSendStateAndDetails(long id, String newState, Long packetId, Double lat, Double lon) {
            for (Message m : messages) {
                if (m.getId() == id) {
                    m.setSendState(newState);
                    m.setPacketId(packetId);
                    m.setLatitude(lat);
                    m.setLongitude(lon);
                    break;
                }
            }
        }

        @Override
        public int markLatestSendingFailed() {
            for (int i = messages.size() - 1; i >= 0; i--) {
                Message m = messages.get(i);
                if (Message.STATE_SENDING.equalsIgnoreCase(m.getSendState())) {
                    m.setSendState(Message.STATE_FAILED);
                    return 1;
                }
            }
            return 0;
        }

        @Override
        public void deleteAll() {
            messages.clear();
        }
    }

    private static class FakeIncidentDao implements IncidentDao {
        final List<Incident> incidents = new ArrayList<>();

        @Override
        public long insert(Incident incident) {
            incidents.add(incident);
            return incidents.size();
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
}
