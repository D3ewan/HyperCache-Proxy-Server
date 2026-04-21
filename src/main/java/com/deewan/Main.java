package com.deewan;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.deewan.ProxyServer.MAX_CLIENTS;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
public class Main {
    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.out.println("Usage: java ProxyServer <port>");
            System.exit(1);
        }

        int port = Integer.parseInt(args[0]);
        System.out.println("Setting Proxy Server Port: " + port);

        ServerSocket serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(port));
        System.out.println("Binding on port: " + port);

        ExecutorService threadPool = Executors.newFixedThreadPool(MAX_CLIENTS);

        System.out.println("Proxy server listening...");

        try {
            while (true) {
                Socket clientSocket = serverSocket.accept();
                InetSocketAddress clientAddr = (InetSocketAddress) clientSocket.getRemoteSocketAddress();
                System.out.println("Client connected with port: " + clientAddr.getPort() +
                        " and IP: " + clientAddr.getAddress().getHostAddress());

                threadPool.submit(new ProxyServer.ClientHandler(clientSocket));
            }
        } finally {
            threadPool.shutdown();
            serverSocket.close();
        }
    }
}