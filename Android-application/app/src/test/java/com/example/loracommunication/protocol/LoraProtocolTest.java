package com.example.loracommunication.protocol;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class LoraProtocolTest {

    @Before
    public void setUp() {
        LoraProtocol.resetPacketIdGeneratorForTesting();
    }

    // --- Phone -> Node Command Tests ---

    @Test
    public void testBuildMsgCommand_valid() {
        String cmd = LoraProtocol.buildMsgCommand("Hello Node!");
        assertEquals("MSG,Hello Node!", cmd);
    }

    @Test
    public void testBuildMsgCommand_withPipe() {
        String cmd = LoraProtocol.buildMsgCommand("Message | with | pipes");
        assertEquals("MSG,Message | with | pipes", cmd);
    }

    @Test
    public void testBuildMsgCommand_withCommas() {
        String cmd = LoraProtocol.buildMsgCommand("water, food, shelter");
        assertEquals("MSG,water, food, shelter", cmd);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testBuildMsgCommand_empty() {
        LoraProtocol.buildMsgCommand("");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testBuildMsgCommand_whitespaceOnly() {
        LoraProtocol.buildMsgCommand("   ");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testBuildMsgCommand_null() {
        LoraProtocol.buildMsgCommand(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testBuildMsgCommand_withNewline() {
        LoraProtocol.buildMsgCommand("Hello\nWorld");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testBuildMsgCommand_withCarriageReturn() {
        LoraProtocol.buildMsgCommand("Hello\rWorld");
    }

    @Test
    public void testBuildMsgCommand_maxLengthExact() {
        // Max message text length is 100 characters.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append('A');
        }
        String text100 = sb.toString();
        String cmd = LoraProtocol.buildMsgCommand(text100);
        assertEquals(104, cmd.length()); // "MSG," + 100 chars
        assertTrue(cmd.startsWith("MSG,"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testBuildMsgCommand_exceedsMaxLength() {
        // 101 chars => exceeds 100 max limit
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 101; i++) {
            sb.append('A');
        }
        LoraProtocol.buildMsgCommand(sb.toString());
    }

    @Test
    public void testBuildSosCommand() {
        assertEquals("SOS", LoraProtocol.buildSosCommand());
    }

    @Test
    public void testBuildStatusCommand() {
        assertEquals("STATUS", LoraProtocol.buildStatusCommand());
    }

    @Test
    public void testBuildPingCommand() {
        assertEquals("PING", LoraProtocol.buildPingCommand());
    }

    @Test
    public void testBuildReadCommand_matchesExpectedString() {
        // Requirement F: Verify the generated READ command string matches "READ\n" exactly.
        assertEquals("READ\n", LoraProtocol.buildReadCommand());
        assertEquals("READ\n", CommandBuilder.read());
    }

    // --- Node -> Phone Line Parsing Tests (New Firmware Format) ---

    @Test
    public void testParseHello_ready() {
        ParsedLine line = LoraProtocol.parseLine("HELLO,NODE A\r\n");
        assertEquals(ParsedLine.LineType.READY, line.getLineType());
        assertEquals("NODE A", line.getNode());
    }

    @Test
    public void testParsePong_bare() {
        ParsedLine line = LoraProtocol.parseLine("PONG\n");
        assertEquals(ParsedLine.LineType.PONG, line.getLineType());
    }

    @Test
    public void testParseStatus_commaFormat_gpsFix() {
        ParsedLine line = LoraProtocol.parseLine("STATUS,A,GPS,12.345600,77.567800,0,0\n");
        assertEquals(ParsedLine.LineType.STATUS, line.getLineType());
        assertEquals("NODE A", line.getNode());
        assertTrue(line.isMpuReady());
        assertTrue(line.hasGpsFix());
        assertTrue(line.isLoraReady());
        assertEquals("GPS", line.getLocSource());
    }

    @Test
    public void testParseStatus_commaFormat_phoneFallback() {
        ParsedLine line = LoraProtocol.parseLine("STATUS,B,PHONE,12.345600,77.567800,0,0\n");
        assertEquals(ParsedLine.LineType.STATUS, line.getLineType());
        assertEquals("NODE B", line.getNode());
        assertTrue(line.isMpuReady());
        assertFalse(line.hasGpsFix());
        assertTrue(line.isLoraReady());
        assertEquals("PHONE", line.getLocSource());
    }

    @Test
    public void testParseStatus_commaFormat_none() {
        ParsedLine line = LoraProtocol.parseLine("STATUS,A,NONE,NA,NA,0,0\n");
        assertEquals(ParsedLine.LineType.STATUS, line.getLineType());
        assertEquals("NODE A", line.getNode());
        assertTrue(line.isMpuReady());
        assertFalse(line.hasGpsFix());
        assertTrue(line.isLoraReady());
        assertEquals("NONE", line.getLocSource());
    }

    @Test
    public void testParseNeedGps() {
        ParsedLine line = LoraProtocol.parseLine("NEED_GPS\n");
        assertEquals(ParsedLine.LineType.NEED_GPS, line.getLineType());
    }

    @Test
    public void testParseMsgIn_withCommas() {
        ParsedLine line = LoraProtocol.parseLine("MSG_IN,A,water, food, shelter\n");
        assertEquals(ParsedLine.LineType.RX, line.getLineType());
        LoraPacket packet = line.getPacket();
        assertNotNull(packet);
        assertEquals(LoraPacket.Type.MSG, packet.getType());
        assertEquals("NODE A", packet.getNode());
        assertEquals("water, food, shelter", packet.getText());
        assertFalse(packet.hasGpsFix());
        assertNotNull(packet.getId());
    }

    @Test
    public void testParseMsgIn_uniqueIncreasingIds() {
        ParsedLine line1 = LoraProtocol.parseLine("MSG_IN,A,hello");
        ParsedLine line2 = LoraProtocol.parseLine("MSG_IN,A,hello");
        assertNotEquals(line1.getPacket().getId(), line2.getPacket().getId());
        long id1 = Long.parseLong(line1.getPacket().getId());
        long id2 = Long.parseLong(line2.getPacket().getId());
        assertEquals(id1 + 1, id2);
    }

    @Test
    public void testParseSosIn_validGps() {
        ParsedLine line = LoraProtocol.parseLine("SOS_IN,A,SOS,13.082700,80.270700,GPS\n");
        assertEquals(ParsedLine.LineType.RX, line.getLineType());
        LoraPacket packet = line.getPacket();
        assertNotNull(packet);
        assertEquals(LoraPacket.Type.SOS, packet.getType());
        assertEquals("NODE A", packet.getNode());
        assertTrue(packet.hasGpsFix());
        assertEquals(13.082700, packet.getLatitude(), 0.000001);
        assertEquals(80.270700, packet.getLongitude(), 0.000001);
    }

    @Test
    public void testParseSosIn_naCoordinates_andFallReason() {
        ParsedLine line = LoraProtocol.parseLine("SOS_IN,B,FALL,NA,NA,NONE\n");
        assertEquals(ParsedLine.LineType.RX, line.getLineType());
        LoraPacket packet = line.getPacket();
        assertNotNull(packet);
        assertEquals(LoraPacket.Type.FALL, packet.getType());
        assertEquals("NODE B", packet.getNode());
        assertFalse(packet.hasGpsFix());
        assertEquals(0.0, packet.getLatitude(), 0.000001);
        assertEquals(0.0, packet.getLongitude(), 0.000001);
    }

    @Test
    public void testParseMsgSent_okAndFail() {
        ParsedLine ok = LoraProtocol.parseLine("MSG_SENT,OK\n");
        assertEquals(ParsedLine.LineType.MSG_SENT, ok.getLineType());
        assertTrue(ok.isSuccess());

        ParsedLine fail = LoraProtocol.parseLine("MSG_SENT,FAIL\n");
        assertEquals(ParsedLine.LineType.MSG_SENT, fail.getLineType());
        assertFalse(fail.isSuccess());
    }

    @Test
    public void testParseSosSending_validAndNa() {
        ParsedLine valid = LoraProtocol.parseLine("SOS_SENDING,BUTTON,13.082700,80.270700,GPS\n");
        assertEquals(ParsedLine.LineType.SOS_SENDING, valid.getLineType());
        assertEquals("BUTTON", valid.getErrorReason());
        assertTrue(valid.hasGpsFix());
        assertEquals(13.082700, valid.getPacketLatitude(), 0.000001);
        assertEquals(80.270700, valid.getPacketLongitude(), 0.000001);

        ParsedLine na = LoraProtocol.parseLine("SOS_SENDING,FALL,NA,NA,NONE\n");
        assertEquals(ParsedLine.LineType.SOS_SENDING, na.getLineType());
        assertEquals("FALL", na.getErrorReason());
        assertFalse(na.hasGpsFix());
    }

    @Test
    public void testParseSosSent_okAndFail() {
        ParsedLine ok = LoraProtocol.parseLine("SOS_SENT,OK\n");
        assertEquals(ParsedLine.LineType.SOS_SENT, ok.getLineType());
        assertTrue(ok.isSuccess());

        ParsedLine fail = LoraProtocol.parseLine("SOS_SENT,FAIL\n");
        assertEquals(ParsedLine.LineType.SOS_SENT, fail.getLineType());
        assertFalse(fail.isSuccess());
    }

    @Test
    public void testParseSosRead() {
        ParsedLine line = LoraProtocol.parseLine("SOS_READ,B\n");
        assertEquals(ParsedLine.LineType.SOS_READ, line.getLineType());
        assertEquals("NODE B", line.getNode());
    }

    @Test
    public void testParseReadSent_okAndFail() {
        ParsedLine ok = LoraProtocol.parseLine("READ_SENT,OK\n");
        assertEquals(ParsedLine.LineType.READ_SENT, ok.getLineType());
        assertTrue(ok.isSuccess());
        assertEquals("OK", ok.getErrorReason());

        ParsedLine fail = LoraProtocol.parseLine("READ_SENT,FAIL\n");
        assertEquals(ParsedLine.LineType.READ_SENT, fail.getLineType());
        assertFalse(fail.isSuccess());
        assertEquals("FAIL", fail.getErrorReason());
    }

    @Test
    public void testParseReadSent() {
        ParsedLine ok = LoraProtocol.parseLine("READ_SENT,OK\n");
        assertEquals(ParsedLine.LineType.READ_SENT, ok.getLineType());

        ParsedLine fail = LoraProtocol.parseLine("READ_SENT,FAIL\n");
        assertEquals(ParsedLine.LineType.READ_SENT, fail.getLineType());
    }

    @Test
    public void testParseFallNewLines() {
        ParsedLine start = LoraProtocol.parseLine("FALL,START,120\n");
        assertEquals(ParsedLine.LineType.FALL_MONITORING, start.getLineType());
        assertEquals(120, start.getFallDurationSec());

        ParsedLine cancel = LoraProtocol.parseLine("FALL,CANCEL\n");
        assertEquals(ParsedLine.LineType.FALL_CANCELLED, cancel.getLineType());

        ParsedLine sos = LoraProtocol.parseLine("FALL,SOS\n");
        assertEquals(ParsedLine.LineType.FALL_SOS, sos.getLineType());
    }

    @Test
    public void testParseErrBusy() {
        ParsedLine busy = LoraProtocol.parseLine("ERR,BUSY\n");
        assertEquals(ParsedLine.LineType.ERROR, busy.getLineType());
        assertEquals("BUSY", busy.getErrorReason());
    }

    // --- Backward Compatible Pipe Protocol Tests ---

    @Test
    public void testParseReady_pipe() {
        ParsedLine line = LoraProtocol.parseLine("READY|NODE A\r\n");
        assertEquals(ParsedLine.LineType.READY, line.getLineType());
        assertEquals("NODE A", line.getNode());
    }

    @Test
    public void testParsePong_pipe() {
        ParsedLine line = LoraProtocol.parseLine("PONG|NODE B\n");
        assertEquals(ParsedLine.LineType.PONG, line.getLineType());
        assertEquals("NODE B", line.getNode());
    }

    @Test
    public void testParseStatus_readyAndFix_pipe() {
        ParsedLine line = LoraProtocol.parseLine("STATUS|NODE A|MPU=READY|GPS=FIX|LORA=READY\n");
        assertEquals(ParsedLine.LineType.STATUS, line.getLineType());
        assertEquals("NODE A", line.getNode());
        assertTrue(line.isMpuReady());
        assertTrue(line.hasGpsFix());
        assertTrue(line.isLoraReady());
    }

    @Test
    public void testParseStatus_notFoundAndNoFix_pipe() {
        ParsedLine line = LoraProtocol.parseLine("STATUS|NODE B|MPU=NOT_FOUND|GPS=NO_FIX|LORA=READY");
        assertEquals(ParsedLine.LineType.STATUS, line.getLineType());
        assertEquals("NODE B", line.getNode());
        assertFalse(line.isMpuReady());
        assertFalse(line.hasGpsFix());
        assertTrue(line.isLoraReady());
    }

    @Test
    public void testParseFallMonitoring_pipe() {
        ParsedLine line = LoraProtocol.parseLine("FALL_MONITORING|NODE A\n");
        assertEquals(ParsedLine.LineType.FALL_MONITORING, line.getLineType());
        assertEquals("NODE A", line.getNode());
    }

    @Test
    public void testParseFallCancelled_pipe() {
        ParsedLine line = LoraProtocol.parseLine("FALL_CANCELLED|NODE A\n");
        assertEquals(ParsedLine.LineType.FALL_CANCELLED, line.getLineType());
        assertEquals("NODE A", line.getNode());
    }

    @Test
    public void testParseErrors_pipe() {
        ParsedLine empty = LoraProtocol.parseLine("ERROR|EMPTY_MESSAGE");
        assertEquals(ParsedLine.LineType.ERROR, empty.getLineType());
        assertEquals("EMPTY_MESSAGE", empty.getErrorReason());

        ParsedLine unknown = LoraProtocol.parseLine("ERROR|UNKNOWN_COMMAND");
        assertEquals(ParsedLine.LineType.ERROR, unknown.getLineType());
        assertEquals("UNKNOWN_COMMAND", unknown.getErrorReason());

        ParsedLine tooLong = LoraProtocol.parseLine("ERROR|COMMAND_TOO_LONG");
        assertEquals(ParsedLine.LineType.ERROR, tooLong.getLineType());
        assertEquals("COMMAND_TOO_LONG", tooLong.getErrorReason());
    }

    @Test
    public void testParseTxMsg_withValidGps() {
        String raw = "TX|MSG|NODE A|42|12.971600|77.594600|Hello world";
        ParsedLine line = LoraProtocol.parseLine(raw);
        assertEquals(ParsedLine.LineType.TX, line.getLineType());
        assertEquals("NODE A", line.getNode());

        LoraPacket packet = line.getPacket();
        assertNotNull(packet);
        assertEquals(LoraPacket.Type.MSG, packet.getType());
        assertEquals("NODE A", packet.getNode());
        assertEquals("42", packet.getId());
        assertTrue(packet.hasGpsFix());
        assertEquals(12.971600, packet.getLatitude(), 0.000001);
        assertEquals(77.594600, packet.getLongitude(), 0.000001);
        assertEquals("Hello world", packet.getText());
    }

    @Test
    public void testParseRxMsg_withInvalidGpsAndPipesInText() {
        String raw = "RX|MSG|NODE B|99|INVALID|INVALID|Meeting at 5|6 PM room A|B";
        ParsedLine line = LoraProtocol.parseLine(raw);
        assertEquals(ParsedLine.LineType.RX, line.getLineType());
        assertEquals("NODE B", line.getNode());

        LoraPacket packet = line.getPacket();
        assertNotNull(packet);
        assertEquals(LoraPacket.Type.MSG, packet.getType());
        assertEquals("NODE B", packet.getNode());
        assertEquals("99", packet.getId());
        assertFalse(packet.hasGpsFix());
        assertEquals(0.0, packet.getLatitude(), 0.000001);
        assertEquals(0.0, packet.getLongitude(), 0.000001);
        assertEquals("Meeting at 5|6 PM room A|B", packet.getText());
    }

    @Test
    public void testParseUnknownOrMalformed() {
        ParsedLine nullLine = LoraProtocol.parseLine(null);
        assertEquals(ParsedLine.LineType.UNKNOWN, nullLine.getLineType());

        ParsedLine emptyLine = LoraProtocol.parseLine("");
        assertEquals(ParsedLine.LineType.UNKNOWN, emptyLine.getLineType());

        ParsedLine randomLine = LoraProtocol.parseLine("SOME_GARBAGE_TEXT");
        assertEquals(ParsedLine.LineType.UNKNOWN, randomLine.getLineType());

        ParsedLine malformedTx = LoraProtocol.parseLine("TX|INVALID_PACKET");
        assertEquals(ParsedLine.LineType.UNKNOWN, malformedTx.getLineType());
    }

    // --- Phone GPS Command Tests ---

    @Test
    public void testCommandBuilderLoc_valid() {
        String cmd = CommandBuilder.loc(12.971599, 77.594566);
        assertEquals("GPS,12.971599,77.594566", cmd);
    }

    @Test
    public void testCommandBuilderLoc_formatting6Decimals() {
        String cmd = CommandBuilder.loc(12.1, -77.2);
        assertEquals("GPS,12.100000,-77.200000", cmd);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCommandBuilderLoc_rejectsNaN() {
        CommandBuilder.loc(Double.NaN, 77.0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCommandBuilderLoc_rejectsInfinite() {
        CommandBuilder.loc(12.0, Double.POSITIVE_INFINITY);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCommandBuilderLoc_rejectsLatitudeOutOfRange() {
        CommandBuilder.loc(90.0001, 77.0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCommandBuilderLoc_rejectsLongitudeOutOfRange() {
        CommandBuilder.loc(12.0, -180.0001);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCommandBuilderLoc_rejectsZeroZero() {
        CommandBuilder.loc(0.0, 0.0);
    }

    @Test
    public void testParseLocOk() {
        ParsedLine line = LoraProtocol.parseLine("LOC_OK|NODE A\r\n");
        assertEquals(ParsedLine.LineType.LOC_OK, line.getLineType());
        assertEquals("NODE A", line.getNode());
    }

    @Test
    public void testParseErrorBadLoc() {
        ParsedLine line = LoraProtocol.parseLine("ERROR|BAD_LOC\n");
        assertEquals(ParsedLine.LineType.ERROR, line.getLineType());
        assertEquals(LoraProtocol.ERROR_BAD_LOC, line.getErrorReason());
    }

    @Test
    public void testPureJavaProtocolHasNoAndroidClasses() {
        Class<?>[] protocolClasses = new Class<?>[]{
                LoraProtocol.class,
                CommandBuilder.class,
                LoraPacket.class,
                LoraPacket.Type.class,
                ParsedLine.class,
                ParsedLine.LineType.class,
                PacketDeduplicator.class
        };

        for (Class<?> clazz : protocolClasses) {
            assertEquals("com.example.loracommunication.protocol", clazz.getPackage().getName());
            for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
                assertFalse("Field " + field.getName() + " in " + clazz.getSimpleName() + " must not reference android.* classes",
                        field.getType().getName().startsWith("android."));
            }
            for (java.lang.reflect.Method method : clazz.getDeclaredMethods()) {
                assertFalse("Return type of " + method.getName() + " in " + clazz.getSimpleName() + " must not reference android.* classes",
                        method.getReturnType().getName().startsWith("android."));
                for (Class<?> param : method.getParameterTypes()) {
                    assertFalse("Parameter in " + method.getName() + " in " + clazz.getSimpleName() + " must not reference android.* classes",
                            param.getName().startsWith("android."));
                }
            }
        }
    }
}
