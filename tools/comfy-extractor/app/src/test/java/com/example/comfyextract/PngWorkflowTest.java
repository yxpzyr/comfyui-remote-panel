package com.example.comfyextract;

import static org.junit.Assert.*;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;

public class PngWorkflowTest {
    private final String workflow = "{\"nodes\":[],\"links\":[]}";
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private static byte[] pack(String type, byte[] payload) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(out);
        data.writeInt(payload.length);
        byte[] code = type.getBytes(StandardCharsets.US_ASCII);
        data.write(code);
        data.write(payload);
        CRC32 crc = new CRC32();
        crc.update(code);
        crc.update(payload);
        data.writeInt((int)crc.getValue());
        return out.toByteArray();
    }
    private static byte[] join(byte[] ... values) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] value: values) out.write(value);
        return out.toByteArray();
    }
    private static byte[] png(String type, byte[] payload) throws IOException {
        return join(new byte[]{(byte)137,80,78,71,13,10,26,10},
            pack("IHDR", new byte[]{0,0,0,1,0,0,0,1,8,2,0,0,0}),
            pack(type,payload),pack("IEND",new byte[0]));
    }
    private static byte[] zip(byte[] input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(out)) {
            deflater.write(input);
        }
        return out.toByteArray();
    }
    @Test public void textPNG() throws Exception {
        byte[] text = join(bytes("workflow"),new byte[]{0},bytes(workflow));
        assertEquals(workflow, MainActivity.PngWorkflow.extract(new ByteArrayInputStream(png("tEXt",text))));
    }
    @Test public void compressedTextPNG() throws Exception {
        byte[] data = join(bytes("workflow"),new byte[]{0,0},zip(bytes(workflow)));
        assertEquals(workflow, MainActivity.PngWorkflow.extract(new ByteArrayInputStream(png("zTXt",data))));
    }
    @Test public void internationalTextPNG() throws Exception {
        byte[] data = join(bytes("workflow"),new byte[]{0,0,0,0,0},bytes(workflow));
        assertEquals(workflow, MainActivity.PngWorkflow.extract(new ByteArrayInputStream(png("iTXt",data))));
    }
    @Test public void compressedInternationalPNG() throws Exception {
        byte[] data = join(bytes("workflow"),new byte[]{0,1,0,0,0},zip(bytes(workflow)));
        assertEquals(workflow, MainActivity.PngWorkflow.extract(new ByteArrayInputStream(png("iTXt",data))));
    }
    @Test public void missingWorkflowFails() throws Exception {
        try {
            MainActivity.PngWorkflow.extract(new ByteArrayInputStream(png("tEXt",join(bytes("prompt"),new byte[]{0},bytes("{}")))));
            fail("missing workflow should fail");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("workflow"));
        }
    }
}
