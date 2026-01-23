package io.github.chains_project.aotp;

import java.io.EOFException;
import java.io.FileInputStream;
import java.io.IOException;
import com.google.common.io.LittleEndianDataInputStream;

public class Main {

    // Magic number for AOTCache files
    // https://github.com/openjdk/jdk/blob/6f6966b28b2c5a18b001be49f5db429c667d7a8f/src/hotspot/share/include/cds.h#L39
    private static final int AOT_MAGIC = 0xf00baba2;
    
    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: java Main <path-to-aot-file>");
            System.exit(1);
        }
        
        String filePath = args[0];
        
        try (FileInputStream fis = new FileInputStream(filePath);
             LittleEndianDataInputStream dis = new LittleEndianDataInputStream(fis)) {
            
            int magic = dis.readInt();

            if (magic == AOT_MAGIC) {
                System.out.println("Valid AOTCache file");
            } else {
                String actualMagic = String.format("%08x", magic);
                System.out.println("Invalid AOTCache file: magic number mismatch (actual: " + actualMagic + ")");
                System.exit(1);
            }

            // crc32 checksum
            dis.readInt();

            // version
            int version = dis.readInt();
            System.out.println("Version: " + version);

            
        } catch (EOFException e) {
            System.out.println("Invalid AOTCache file: file too short");
            System.exit(1);
        } catch (IOException e) {
            System.err.println("Error reading file: " + e.getMessage());
            System.exit(1);
        }
    }
}