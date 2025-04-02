package shared;

import java.io.Serializable;

public class FilePacket implements Serializable {
    private final String fileName;
    private final byte[] data;

    public FilePacket(String fileName, byte[] data) {
        this.fileName = fileName;
        this.data = data;
    }

    public String getFileName() {
        return fileName;
    }

    public byte[] getData() {
        return data;
    }
}