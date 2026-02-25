# Socket-read操作

```text
try (InputStream inputStream = socket.getInputStream(); OutputStream outputStream = socket.getOutputStream()) {
    // 发送请求
    outputStream.write(httpRequest.getBytes(StandardCharsets.UTF_8));

    // 接收请求
    byte[] buffer = new byte[1024];
    int nRead;
    while ((nRead = inputStream.read(buffer)) > 0 ) {
        String content = new String(buffer, 0, nRead, StandardCharsets.UTF_8);
        System.out.print(content);
    }
}


inputStream -> Socket$SocketInputStream
```

```text
public class Socket implements java.io.Closeable {
    
    // the underlying SocketImpl, may be null, may be swapped when connecting
    private volatile SocketImpl impl;   // java.net.SocksSocketImpl
    
    
    // 💯💯💯 Socket的getInputStream和getOutputStream返回的是一个代理对象,代理对象底层用的是Nio的相关技术
    
    // An InputStream that delegates read/ available operations to an underlying input stream. 
    // The close method is overridden to close the Socket. This class is instrumented by Java Flight Recorder (JFR) to get socket I/ O events.
    private static class SocketInputStream extends InputStream {
        private final Socket parent;
        private final InputStream in;
        SocketInputStream(Socket parent, InputStream in) {
            this.parent = parent;
            this.in = in;
        }
        @Override
        public int read() throws IOException {
            byte[] a = new byte[1];
            int n = read(a, 0, 1);
            return (n > 0) ? (a[0] & 0xff) : -1;
        }
        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            try {
                return in.read(b, off, len);
            } catch (SocketTimeoutException e) {
                throw e;
            } catch (InterruptedIOException e) {
                Thread thread = Thread.currentThread();
                if (thread.isVirtual() && thread.isInterrupted()) {
                    close();
                    throw new SocketException("Closed by interrupt");
                }
                throw e;
            }
        }
        @Override
        public int available() throws IOException {
            return in.available();
        }
        @Override
        public void close() throws IOException {
            parent.close();
        }
    }
    
    
    // An OutputStream that delegates write operations to an underlying output stream. The close method is overridden to close the Socket.
    // This class is instrumented by Java Flight Recorder (JFR) to get socket I/O events.
    private static class SocketOutputStream extends OutputStream {
        private final Socket parent;
        private final OutputStream out;
        SocketOutputStream(Socket parent, OutputStream out) {
            this.parent = parent;
            this.out = out;
        }
        @Override
        public void write(int b) throws IOException {
            byte[] a = new byte[] { (byte) b };
            write(a, 0, 1);
        }
        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            try {
                out.write(b, off, len);
            } catch (InterruptedIOException e) {
                Thread thread = Thread.currentThread();
                if (thread.isVirtual() && thread.isInterrupted()) {
                    close();
                    throw new SocketException("Closed by interrupt");
                }
                throw e;
            }
        }
        @Override
        public void close() throws IOException {
            parent.close();
        }
    }   
}
```

```text
class SocksSocketImpl extends DelegatingSocketImpl implements SocksConsts {
    protected final SocketImpl delegate; // sun.nio.ch.NioSocketImpl
}
```

```text
public final class NioSocketImpl extends SocketImpl implements PlatformSocketImpl {

    @Override
    protected InputStream getInputStream() {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                byte[] a = new byte[1];
                int n = read(a, 0, 1);
                return (n > 0) ? (a[0] & 0xff) : -1;
            }
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                return NioSocketImpl.this.read(b, off, len);
            }
            @Override
            public int available() throws IOException {
                return NioSocketImpl.this.available();
            }
            @Override
            public void close() throws IOException {
                NioSocketImpl.this.close();
            }
        };
    }
    
    private int read(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        if (len == 0) {
            return 0;
        } else {
            readLock.lock();
            try {
                // emulate legacy behavior to return -1, even if socket is closed
                if (readEOF)
                    return -1;
                // read up to MAX_BUFFER_SIZE bytes
                int size = Math.min(len, MAX_BUFFER_SIZE);
                int n = implRead(b, off, size);
                if (n == -1)
                    readEOF = true;
                return n;
            } finally {
                readLock.unlock();
            }
        }
    }
    
    private int implRead(byte[] b, int off, int len) throws IOException {
        int n = 0;
        FileDescriptor fd = beginRead();
        try {
            if (connectionReset)
                throw new SocketException("Connection reset");
            if (isInputClosed)
                return -1;
            int timeout = this.timeout;
            configureNonBlockingIfNeeded(fd, timeout > 0);
            if (timeout > 0) {
                // read with timeout
                n = timedRead(fd, b, off, len, MILLISECONDS.toNanos(timeout));
            } else {
                // read, no timeout
                n = tryRead(fd, b, off, len);
                while (IOStatus.okayToRetry(n) && isOpen()) {
                    park(fd, Net.POLLIN);                                            // 💯💯💯 向Poller注册当前fd的接收到数据的事件,当fd有新的数据后才退出该park方法
                    n = tryRead(fd, b, off, len);
                }
            }
            return n;
        } catch (InterruptedIOException e) {
            throw e;
        } catch (ConnectionResetException e) {
            connectionReset = true;
            throw new SocketException("Connection reset");
        } catch (IOException ioe) {
            // throw SocketException to maintain compatibility
            throw asSocketException(ioe);
        } finally {
            endRead(n > 0);
        }
    }
    
    // Disables the current thread for scheduling purposes until the socket is ready for I/ O or is asynchronously closed.
    private void park(FileDescriptor fd, int event) throws IOException {
        park(fd, event, 0);
    }
    
    // Disables the current thread for scheduling purposes until the socket is ready for I/ O or is asynchronously closed, for up to the specified waiting time.
    private void park(FileDescriptor fd, int event, long nanos) throws IOException {
        Thread t = Thread.currentThread();
        if (t.isVirtual()) {                                                             // 💯💯💯 区分虚拟线程和平台线程
            Poller.poll(fdVal(fd), event, nanos, this::isOpen);
            if (t.isInterrupted()) {
                throw new InterruptedIOException();
            }
        } else {
            long millis;
            if (nanos == 0) {
                millis = -1;
            } else {
                millis = NANOSECONDS.toMillis(nanos);
                if (nanos > MILLISECONDS.toNanos(millis)) {
                    // Round up any excess nanos to the nearest millisecond to
                    // avoid parking for less than requested.
                    millis++;
                }
            }
            Net.poll(fd, event, millis);                                               // Polls a file descriptor for events. timeout – the timeout to wait; 0 to not wait, -1 to wait indefinitely
        }
    }    
}
```

