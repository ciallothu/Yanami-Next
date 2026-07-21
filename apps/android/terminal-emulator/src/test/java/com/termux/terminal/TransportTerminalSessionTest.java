package com.termux.terminal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class TransportTerminalSessionTest {

    @Test
    public void transportSessionForwardsInputResizeAndClosesOnce() {
        RecordingTransport transport = new RecordingTransport();
        TerminalSession session = new TerminalSession(transport, 3000, new NoOpClient());

        session.updateSize(80, 24, 8, 16);
        assertNotNull(session.getEmulator());
        assertFalse(session.isOsc52ClipboardAllowed());
        assertEquals(1, transport.resizeCount);
        assertEquals(80, transport.columns);
        assertEquals(24, transport.rows);

        byte[] input = "hello".getBytes(StandardCharsets.UTF_8);
        session.write(input, 0, input.length);
        session.writeCodePoint(true, 0x00E9);
        assertArrayEquals(
                new byte[] {'h', 'e', 'l', 'l', 'o', 27, (byte) 0xC3, (byte) 0xA9},
                transport.output.toByteArray()
        );

        session.updateSize(100, 40, 9, 18);
        assertEquals(2, transport.resizeCount);
        assertEquals(100, session.getEmulator().mColumns);
        assertEquals(40, session.getEmulator().mRows);

        assertTrue(session.isRunning());
        session.finishIfRunning();
        session.finishIfRunning();
        assertFalse(session.isRunning());
        assertEquals(1, transport.closeCount);
    }

    @Test
    public void emulatorGeneratedDeviceStatusReplyUsesRemoteTransport() {
        RecordingTransport transport = new RecordingTransport();
        TerminalSession session = new TerminalSession(transport, 3000, new NoOpClient());
        session.updateSize(80, 24, 8, 16);

        byte[] query = "\u001b[6n".getBytes(StandardCharsets.UTF_8);
        session.getEmulator().append(query, query.length);

        assertEquals("\u001b[1;1R", transport.output.toString(StandardCharsets.UTF_8));
    }

    private static final class RecordingTransport implements TerminalTransport {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        int columns;
        int rows;
        int resizeCount;
        int closeCount;

        @Override
        public void write(byte[] data, int offset, int count) {
            output.write(data, offset, count);
        }

        @Override
        public void resize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
            this.columns = columns;
            this.rows = rows;
            resizeCount++;
        }

        @Override
        public void close() {
            closeCount++;
        }
    }

    private static final class NoOpClient implements TerminalSessionClient {
        @Override public void onTextChanged(TerminalSession changedSession) {}
        @Override public void onTitleChanged(TerminalSession changedSession) {}
        @Override public void onSessionFinished(TerminalSession finishedSession) {}
        @Override public void onCopyTextToClipboard(TerminalSession session, String text) {}
        @Override public void onPasteTextFromClipboard(TerminalSession session) {}
        @Override public void onBell(TerminalSession session) {}
        @Override public void onColorsChanged(TerminalSession session) {}
        @Override public void onTerminalCursorStateChange(boolean state) {}
        @Override public void setTerminalShellPid(TerminalSession session, int pid) {}
        @Override public Integer getTerminalCursorStyle() { return 0; }
        @Override public void logError(String tag, String message) {}
        @Override public void logWarn(String tag, String message) {}
        @Override public void logInfo(String tag, String message) {}
        @Override public void logDebug(String tag, String message) {}
        @Override public void logVerbose(String tag, String message) {}
        @Override public void logStackTraceWithMessage(String tag, String message, Exception e) {}
        @Override public void logStackTrace(String tag, Exception e) {}
    }
}
