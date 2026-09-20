package dev.jsinco.brewery.bukkit.database;

import dev.jsinco.brewery.database.Session;
import dev.jsinco.brewery.database.sql.DatabaseDriver;
import dev.jsinco.brewery.database.sql.SqlDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.Executor;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlDatabaseTest {

    @TempDir
    Path tempDirectory;

    @Test
    void closeDrainsQueuedWorkAndIsIdempotent() throws Exception {
        SqlDatabase database = new SqlDatabase(DatabaseDriver.SQLITE);
        database.init(tempDirectory.toFile());
        AtomicBoolean queuedWorkFinished = new AtomicBoolean();
        database.startSession((executor, ignored) -> new TestSession(executor))
                .execute(() -> queuedWorkFinished.set(true));

        database.close();

        assertTrue(queuedWorkFinished.get());
        assertDoesNotThrow(database::close);

        SqlDatabase reopened = new SqlDatabase(DatabaseDriver.SQLITE);
        reopened.init(tempDirectory.toFile());
        try (Connection connection = reopened.getConnection();
             Statement statement = connection.createStatement();
             ResultSet versions = statement.executeQuery("SELECT version FROM version")) {
            assertTrue(versions.next());
            assertEquals(3, versions.getInt("version"));
        } finally {
            reopened.close();
        }
    }

    @Test
    void closeDrainsDependentWorkQueuedBehindFlushBarrier() throws Exception {
        SqlDatabase database = new SqlDatabase(DatabaseDriver.SQLITE);
        database.init(tempDirectory.toFile());
        TestSession session = database.startSession((executor, ignored) -> new TestSession(executor));
        AtomicBoolean dependentWorkFinished = new AtomicBoolean();
        CompletableFuture<Void> first = session.execute(() -> {
        });
        first.thenRunAsync(() -> dependentWorkFinished.set(true), session.executor());

        database.close();

        assertTrue(dependentWorkFinished.get());
    }

    private record TestSession(Executor executor) implements Session<TestSession> {
    }
}
