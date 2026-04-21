# Java Multi-Threaded HTTP Proxy Server

A lightweight, multi-threaded HTTP proxy server written in pure Java. It handles concurrent client connections, forwards HTTP `GET` requests to remote servers, and caches the responses locally using an in-memory Least Recently Used (LRU) cache mechanism to optimize subsequent requests.

## Features

* **Concurrency**: Utilizes Java's `ExecutorService` thread pool and `Semaphore` to efficiently manage up to 400 concurrent client connections.
* **LRU Caching**: Implements a custom thread-safe Linked List cache to store HTTP responses, minimizing bandwidth and latency for repeated requests.
* **Memory Management**: Cache is strictly capped (default 200MB total, 10MB per element) with automatic eviction of the least recently used elements.
* **Error Handling**: Generates standard HTTP error responses (400, 403, 404, 500, 501, 505) for malformed requests or unsupported methods.

## Prerequisites

* Java Development Kit (JDK) 8 or higher.

## Building and Running

1. Clone the repository:
   ```bash
   git clone https://github.com/D3ewan/HyperCache-Proxy-Server.git
   cd HyperCache-Proxy-Server
   ```

2. Run using the script

For Windows
```bash
.\run.bat
```

For Ubuntu
```bash
./run.sh
```
