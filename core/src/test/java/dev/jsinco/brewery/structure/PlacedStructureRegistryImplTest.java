package dev.jsinco.brewery.structure;

import dev.jsinco.brewery.api.breweries.StructureHolder;
import dev.jsinco.brewery.api.structure.MultiblockStructure;
import dev.jsinco.brewery.api.structure.StructureType;
import dev.jsinco.brewery.api.util.BreweryKey;
import dev.jsinco.brewery.api.vector.BreweryLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlacedStructureRegistryImplTest {

    private static final StructureType<TestHolder> TYPE = new StructureType<>(
            BreweryKey.parse("test_structure"), TestHolder.class
    );

    @Test
    void clearRemovesPositionAndTypedIndexes() {
        PlacedStructureRegistryImpl registry = new PlacedStructureRegistryImpl();
        BreweryLocation location = new BreweryLocation(1, 2, 3, UUID.randomUUID());
        TestStructure structure = new TestStructure(location);
        structure.setHolder(new TestHolder(structure));
        registry.registerStructure(structure);

        assertEquals(1, registry.countStructureType(TYPE));
        assertEquals(1, registry.getStructures(TYPE).size());

        registry.clear();

        assertEquals(0, registry.countStructureType(TYPE));
        assertEquals(0, registry.getStructures(TYPE).size());
        assertEquals(0, registry.getStructures(List.of(location)).size());
    }

    @Test
    void typedStructureViewCannotMutateRegistry() {
        PlacedStructureRegistryImpl registry = new PlacedStructureRegistryImpl();
        assertThrows(UnsupportedOperationException.class,
                () -> registry.getStructures(TYPE).add(new TestStructure(
                        new BreweryLocation(1, 2, 3, UUID.randomUUID()))));
    }

    private static final class TestStructure implements MultiblockStructure<TestHolder> {
        private final BreweryLocation location;
        private TestHolder holder;

        private TestStructure(BreweryLocation location) {
            this.location = location;
        }

        @Override
        public List<BreweryLocation> positions() {
            return List.of(location);
        }

        @Override
        public TestHolder getHolder() {
            return holder;
        }

        @Override
        public void setHolder(TestHolder holder) {
            this.holder = holder;
        }

        @Override
        public BreweryLocation getUnique() {
            return location;
        }
    }

    private record TestHolder(TestStructure structure) implements StructureHolder<TestHolder> {
        @Override
        public MultiblockStructure<TestHolder> getStructure() {
            return structure;
        }

        @Override
        public void destroy(BreweryLocation breweryLocation) {
        }

        @Override
        public StructureType<?> getStructureType() {
            return TYPE;
        }
    }
}
