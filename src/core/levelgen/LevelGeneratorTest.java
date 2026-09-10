package core.levelgen;

import org.junit.Test;
import org.junit.Assert;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.lang.reflect.Field;

public class LevelGeneratorTest {

    private void setupGenerator(LevelGenerator generator, int mapSize, String[] mockLevel) throws Exception {
        Field mapSizeField = LevelGenerator.class.getDeclaredField("mapSize");
        mapSizeField.setAccessible(true);
        mapSizeField.set(generator, mapSize);

        Field levelField = LevelGenerator.class.getDeclaredField("level");
        levelField.setAccessible(true);
        levelField.set(generator, mockLevel);
    }

    @Test
    public void testToCSV_1x1() throws Exception {
        LevelGenerator generator = new LevelGenerator(12345);
        String[] mockLevel = { "A" };
        setupGenerator(generator, 1, mockLevel);

        String testFilename = "test_level_1x1.csv";
        try {
            generator.toCSV(testFilename);
            String actual = new String(Files.readAllBytes(Paths.get(testFilename)));
            Assert.assertEquals("CSV content should match expected format for 1x1", "A,", actual);
        } finally {
            new File(testFilename).delete();
        }
    }

    @Test
    public void testToCSV_2x2() throws Exception {
        LevelGenerator generator = new LevelGenerator(12345);
        String[] mockLevel = { "A", "B", "C", "D" };
        setupGenerator(generator, 2, mockLevel);

        String testFilename = "test_level_2x2.csv";
        try {
            generator.toCSV(testFilename);
            String actual = new String(Files.readAllBytes(Paths.get(testFilename)));
            Assert.assertEquals("CSV content should match expected format for 2x2", "A,B\nC,D", actual);
        } finally {
            new File(testFilename).delete();
        }
    }

    @Test
    public void testToCSV_3x3() throws Exception {
        LevelGenerator generator = new LevelGenerator(12345);
        String[] mockLevel = {
            "A", "B", "C",
            "D", "E", "F",
            "G", "H", "I"
        };
        setupGenerator(generator, 3, mockLevel);

        String testFilename = "test_level_3x3.csv";
        try {
            generator.toCSV(testFilename);
            String actual = new String(Files.readAllBytes(Paths.get(testFilename)));
            Assert.assertEquals("CSV content should match expected format for 3x3", "A,B,C\nD,E,F\nG,H,I", actual);
        } finally {
            new File(testFilename).delete();
        }
    }

    @Test
    public void testToCSV_4x4() throws Exception {
        LevelGenerator generator = new LevelGenerator(12345);
        String[] mockLevel = {
            "A", "B", "C", "D",
            "E", "F", "G", "H",
            "I", "J", "K", "L",
            "M", "N", "O", "P"
        };
        setupGenerator(generator, 4, mockLevel);

        String testFilename = "test_level_4x4.csv";
        try {
            generator.toCSV(testFilename);
            String actual = new String(Files.readAllBytes(Paths.get(testFilename)));
            Assert.assertEquals("CSV content should match expected format for 4x4", "A,B,C,D\nE,F,G,H\nI,J,K,L\nM,N,O,P", actual);
        } finally {
            new File(testFilename).delete();
        }
    }
}
