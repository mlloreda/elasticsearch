/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.test.cluster.local;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.test.cluster.ClusterHandle;
import org.elasticsearch.test.cluster.local.LocalClusterFactory.Node;
import org.elasticsearch.test.cluster.local.model.User;
import org.elasticsearch.test.cluster.util.ExceptionUtils;
import org.elasticsearch.test.cluster.util.Retry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class LocalClusterHandle implements ClusterHandle {
    private static final Logger LOGGER = LogManager.getLogger(LocalClusterHandle.class);
    private static final Duration CLUSTER_UP_TIMEOUT = Duration.ofSeconds(30);

    public final ForkJoinPool executor = new ForkJoinPool(
        Math.max(Runtime.getRuntime().availableProcessors(), 4),
        new ForkJoinPool.ForkJoinWorkerThreadFactory() {
            private final AtomicLong counter = new AtomicLong(0);

            @Override
            public ForkJoinWorkerThread newThread(ForkJoinPool pool) {
                ForkJoinWorkerThread thread = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
                thread.setName(name + "-node-executor-" + counter.getAndIncrement());
                return thread;
            }
        },
        null,
        false
    );
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final String name;
    private final List<Node> nodes;

    public LocalClusterHandle(String name, List<Node> nodes) {
        this.name = name;
        this.nodes = nodes;
    }

    @Override
    public void start() {
        if (started.getAndSet(true) == false) {
            LOGGER.info("Starting Elasticsearch test cluster '{}'", name);
            execute(() -> nodes.parallelStream().forEach(Node::start));
        }
        waitUntilReady();
    }

    @Override
    public void stop(boolean forcibly) {
        if (started.getAndSet(false)) {
            LOGGER.info("Stopping Elasticsearch test cluster '{}', forcibly: {}", name, forcibly);
            execute(() -> nodes.forEach(n -> n.stop(forcibly)));
        } else {
            // Make sure the process is stopped, otherwise wait
            execute(() -> nodes.forEach(n -> n.waitForExit()));
        }
    }

    @Override
    public boolean isStarted() {
        return started.get();
    }

    @Override
    public void close() {
        stop(false);

        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String getHttpAddresses() {
        start();
        return execute(() -> nodes.parallelStream().map(Node::getHttpAddress).collect(Collectors.joining(",")));
    }

    private void waitUntilReady() {
        writeUnicastHostsFile();
        try {
            Retry.retryUntilTrue(CLUSTER_UP_TIMEOUT, Duration.ZERO, () -> {
                Node node = nodes.get(0);
                String scheme = node.getSpec().isSettingTrue("xpack.security.http.ssl.enabled") ? "https" : "http";
                WaitForHttpResource wait = new WaitForHttpResource(scheme, node.getHttpAddress(), nodes.size());
                User credentials = node.getSpec().getUsers().get(0);
                wait.setUsername(credentials.getUsername());
                wait.setPassword(credentials.getPassword());
                return wait.wait(500);
            });
        } catch (TimeoutException e) {
            throw new RuntimeException("Timed out after " + CLUSTER_UP_TIMEOUT + " waiting for cluster '" + name + "' status to be yellow");
        } catch (ExecutionException e) {
            throw new RuntimeException("An error occurred while checking cluster '" + name + "' status.", e);
        }
    }

    private void writeUnicastHostsFile() {
        String transportUris = execute(() -> nodes.parallelStream().map(Node::getTransportEndpoint).collect(Collectors.joining("\n")));
        nodes.forEach(node -> {
            try {
                Path hostsFile = node.getWorkingDir().resolve("config").resolve("unicast_hosts.txt");
                if (Files.notExists(hostsFile)) {
                    Files.writeString(hostsFile, transportUris);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to write unicast_hosts for: " + node, e);
            }
        });
    }

    private <T> T execute(Callable<T> task) {
        try {
            return executor.submit(task).get();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new RuntimeException("An error occurred orchestrating test cluster.", ExceptionUtils.findRootCause(e));
        }
    }

    private void execute(Runnable task) {
        execute(() -> {
            task.run();
            return true;
        });
    }
}
