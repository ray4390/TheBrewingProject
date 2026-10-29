package dev.jsinco.brewery.bukkit.wine;

import dev.jsinco.brewery.bukkit.TheBrewingProject;
import dev.jsinco.brewery.bukkit.testutil.TBPServerMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class WineryRegistryTest {
    ServerMock server;
    TheBrewingProject plugin;
    WineryRegistry wineries;
    PlayerMock owner;
    PlayerMock worker;

    @BeforeEach
    void setup() throws IOException {
        server = MockBukkit.mock(new TBPServerMock());
        plugin = MockBukkit.load(TheBrewingProject.class);
        wineries = new WineryRegistry(plugin);
        owner = server.addPlayer();
        worker = server.addPlayer();
    }

    @AfterEach
    void teardown() {
        MockBukkit.unmock();
    }

    @Test
    void creationPersistsAndNamesAreUnique() throws IOException {
        assertTrue(wineries.create(owner, "Westbridge Cellars").contains("Created winery"));
        assertTrue(wineries.info(owner).contains("Westbridge Cellars"));
        assertTrue(wineries.create(worker, "westbridge cellars").contains("already"));
        WineryRegistry reopened = new WineryRegistry(plugin);
        assertTrue(reopened.info(owner).contains("Westbridge Cellars"));
        assertThrows(IllegalArgumentException.class, () -> reopened.create(worker, "<unsafe>"));
    }

    @Test
    void invitationAndOwnershipSurviveReload() throws IOException {
        assertTrue(wineries.create(owner, "Oak Hall").contains("Created"));
        assertTrue(wineries.invite(owner, worker).contains("Invited"));
        assertTrue(wineries.accept(worker).contains("Joined"));
        assertTrue(wineries.info(worker).contains("2 members"));
        assertTrue(wineries.remove(owner, owner.getUniqueId()).contains("transfer"));
        assertTrue(wineries.transfer(owner, worker.getUniqueId()).contains("transferred"));
        WineryRegistry reopened = new WineryRegistry(plugin);
        assertTrue(reopened.info(worker).contains("Owner"));
        assertTrue(reopened.remove(owner, owner.getUniqueId()).contains("Left"));
    }

    @Test
    void untrustedActorsCannotClaimAnotherWineryOrInviteMembers() throws IOException {
        assertTrue(wineries.create(owner, "Seaside").contains("Created"));
        assertTrue(wineries.invite(worker, owner).contains("Only"));
        assertTrue(wineries.accept(worker).contains("no pending"));
        assertEquals("You do not belong to a winery. Use /winery create <name>.", wineries.info(worker));
    }
}
