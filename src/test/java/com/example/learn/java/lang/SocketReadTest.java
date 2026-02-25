package com.example.learn.java.lang;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class SocketReadTest {

    public static void main(String[] args) throws Exception {
        test01();
    }


    public static void test01() throws IOException {
        String host = "www.example.com";
        int port = 80;


        String httpRequest = "GET / HTTP/1.1\r\n" +
                "Host: " + host + "\r\n" +
                "Connection: close\r\n\r\n";


        // Creates a stream socket and connects it to the specified port number at the specified IP address
        Socket socket = new Socket(InetAddress.getByName(host), port);
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
    }
}
