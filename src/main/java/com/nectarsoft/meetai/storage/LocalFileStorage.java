package com.nectarsoft.meetai.storage;

import com.nectarsoft.meetai.config.MeetAiProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
@RequiredArgsConstructor
public class LocalFileStorage implements FileStorage {

    private final MeetAiProperties props;

    private Path uploadDir;
    private Path tempDir;

    @PostConstruct
    void init() throws IOException {
        uploadDir = Path.of(props.getStorage().getUploadDir());
        tempDir = Path.of(props.getStorage().getTempDir());
        Files.createDirectories(uploadDir);
        Files.createDirectories(tempDir);
    }

    @Override
    public Path saveUpload(String filename, MultipartFile file) throws IOException {
        Path dest = uploadDir.resolve(filename);
        file.transferTo(dest);
        return dest;
    }

    @Override
    public Path saveTempCopy(Path source) throws IOException {
        Path dest = tempDir.resolve(source.getFileName());
        Files.copy(source, dest);
        return dest;
    }

    @Override
    public Path saveTempBytes(String filename, byte[] data) throws IOException {
        Path dest = tempDir.resolve(filename);
        Files.write(dest, data);
        return dest;
    }

    @Override
    public void delete(Path path) throws IOException {
        Files.deleteIfExists(path);
    }
}
