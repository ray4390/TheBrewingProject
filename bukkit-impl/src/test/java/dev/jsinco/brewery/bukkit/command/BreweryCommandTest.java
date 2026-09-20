package dev.jsinco.brewery.bukkit.command;

import dev.jsinco.brewery.bukkit.TheBrewingProject;
import dev.jsinco.brewery.api.effect.DrunkState;
import dev.jsinco.brewery.bukkit.testutil.TBPServerMock;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.exception.UnimplementedOperationException;

import static org.junit.jupiter.api.Assertions.*;

class BreweryCommandTest {

    PlayerMock target;
    TheBrewingProject theBrewingProject;

    @BeforeEach
    void setUp() {
        ServerMock serverMock = MockBukkit.mock(new TBPServerMock());
        theBrewingProject = MockBukkit.load(TheBrewingProject.class);
        target = serverMock.addPlayer();
        target.addAttachment(theBrewingProject, "brewery.command", true);
    }
    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/command/brew_command_invalid_args.csv")
    void onCommand_invalid(String command) {
        assertDoesNotThrow(() -> target.performCommand(command));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/command/create_command_invalid_args.csv")
    void onCreateCommand_invalid(String command) {
        assertDoesNotThrow(() -> target.performCommand(command));
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/command/create_command_valid.csv")
    void onCreateCommand_valid(String command) {
        try {
            target.performCommand(command);
        } catch (UnimplementedOperationException exception) {
            // MockBukkit 26.2 does not implement ItemStack#effectiveName, which
            // is only used to format the post-success message. The item has
            // already been created by this point; do not skip the assertion.
            assertEquals("effectiveName", exception.getStackTrace()[0].getMethodName());
        }
        assertEquals(Material.POTION, target.getInventory().getItemInMainHand().getType(), target.nextMessage());
    }

    @Test
    void setStatus() {
        assertDoesNotThrow(() -> target.performCommand("tbp status info"));
        target.performCommand("tbp status consume alcohol 30 toxins 40");
        DrunkState drunkState = TheBrewingProject.getInstance().getDrunksManager().getDrunkState(target.getUniqueId());
        assertEquals(30, drunkState.modifierValue("alcohol"));
        assertEquals(40, drunkState.modifierValue("toxins"));
        target.performCommand("tbp status set alcohol 10 toxins 20");
        assertDoesNotThrow(() -> target.performCommand("tbp status info"));
        drunkState = TheBrewingProject.getInstance().getDrunksManager().getDrunkState(target.getUniqueId());
        assertEquals(10, drunkState.modifierValue("alcohol"));
        assertEquals(20, drunkState.modifierValue("toxins"));
        target.performCommand("tbp status clear");
        drunkState = TheBrewingProject.getInstance().getDrunksManager().getDrunkState(target.getUniqueId());
        assertNull(drunkState);
    }
}
