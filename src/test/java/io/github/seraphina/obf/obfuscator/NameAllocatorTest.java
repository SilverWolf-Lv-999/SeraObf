package io.github.seraphina.obf.obfuscator;

import io.github.seraphina.obf.common.config.CommonConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NameAllocatorTest {

    @Test
    void cyclesDictionaryWordsWithoutRepeating() {
        NameAllocator allocator = new NameAllocator(7L);
        Set<String> names = new HashSet<>();
        for (int index = 0; index < CommonConfig.METHOD_NAME_WORDS.length * 2; index++) {
            assertTrue(names.add(allocator.nextMethodName()));
        }
        assertTrue(names.containsAll(Set.of(CommonConfig.METHOD_NAME_WORDS)));
    }

    @Test
    void keepsNameKindsInSeparateLists() {
        NameAllocator allocator = new NameAllocator(11L);
        Set<String> methods = new HashSet<>();
        Set<String> fields = new HashSet<>();
        Set<String> classes = new HashSet<>();
        for (int index = 0; index < 32; index++) {
            assertTrue(methods.add(allocator.nextMethodName()));
            assertTrue(fields.add(allocator.nextFieldName()));
            assertTrue(classes.add(allocator.nextClassName()));
        }
        for (String name : classes) {
            assertTrue(!methods.contains(name) && !fields.contains(name), name);
        }
    }

    @Test
    void seedsControlShuffle() {
        assertEquals(draw(new NameAllocator(5L), 20), draw(new NameAllocator(5L), 20));
        assertNotEquals(draw(new NameAllocator(5L), 20), draw(new NameAllocator(6L), 20));
    }

    private static List<String> draw(NameAllocator allocator, int count) {
        List<String> names = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            names.add(allocator.nextClassName());
        }
        return names;
    }
}
