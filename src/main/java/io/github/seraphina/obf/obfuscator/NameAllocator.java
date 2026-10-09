package io.github.seraphina.obf.obfuscator;

import io.github.seraphina.obf.common.config.CommonConfig;

import java.util.Random;

public class NameAllocator {

    private final long seed;
    private final Random random;
    private final Deck classNames;
    private final Deck methodNames;
    private final Deck fieldNames;
    private final Deck packageNames;

    public NameAllocator(long seed) {
        this.seed = seed == 0L ? new Random().nextLong() : seed;
        random = new Random(this.seed);
        classNames = new Deck(CommonConfig.CLASS_NAME_WORDS, random);
        methodNames = new Deck(CommonConfig.METHOD_NAME_WORDS, random);
        fieldNames = new Deck(CommonConfig.FIELD_NAME_WORDS, random);
        packageNames = new Deck(CommonConfig.PACKAGE_NAME_WORDS, random);
    }

    public long getSeed() {
        return seed;
    }

    public String nextClassName() {
        return classNames.next();
    }

    public String nextMethodName() {
        return methodNames.next();
    }

    public String nextFieldName() {
        return fieldNames.next();
    }

    public String nextPackagePath() {
        StringBuilder builder = new StringBuilder();
        int depth = 1 + random.nextInt(3);
        for (int index = 0; index < depth; index++) {
            builder.append(packageNames.next()).append('/');
        }
        return builder.toString();
    }

    private static class Deck {

        private final String[] words;
        private int cursor;
        private int cycle;

        private Deck(String[] words, Random random) {
            this.words = words.clone();
            for (int index = this.words.length - 1; index > 0; index--) {
                int swap = random.nextInt(index + 1);
                String value = this.words[index];
                this.words[index] = this.words[swap];
                this.words[swap] = value;
            }
        }

        private String next() {
            String word = words[cursor++];
            int current = cycle;
            if (cursor == words.length) {
                cursor = 0;
                cycle++;
            }
            return current == 0 ? word : word + (current + 1);
        }
    }
}
