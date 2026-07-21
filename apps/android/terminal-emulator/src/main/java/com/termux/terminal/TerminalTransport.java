package com.termux.terminal;

/**
 * Byte transport used by a {@link TerminalSession} that is backed by a remote PTY.
 *
 * <p>Implementations must preserve the order of {@link #write(byte[], int, int)} calls. All
 * callbacks are invoked on the terminal view's thread and should return quickly.</p>
 */
public interface TerminalTransport {

    /** Write terminal input or an emulator-generated reply to the remote PTY. */
    void write(byte[] data, int offset, int count);

    /** Notify the remote PTY of its current character and pixel dimensions. */
    void resize(int columns, int rows, int cellWidthPixels, int cellHeightPixels);

    /** Close the remote transport. Implementations must make this operation idempotent. */
    void close();
}
