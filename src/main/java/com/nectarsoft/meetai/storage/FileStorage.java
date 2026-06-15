package com.nectarsoft.meetai.storage;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;

public interface FileStorage {

    /** MultipartFile을 업로드 디렉토리에 저장하고 경로를 반환한다. */
    Path saveUpload(String filename, MultipartFile file) throws IOException;

    /** source 파일을 임시 디렉토리에 복사하고 경로를 반환한다. */
    Path saveTempCopy(Path source) throws IOException;

    /** byte[] 데이터를 임시 디렉토리에 저장하고 경로를 반환한다. */
    Path saveTempBytes(String filename, byte[] data) throws IOException;

    /** 파일을 삭제한다. 파일이 없으면 무시한다. */
    void delete(Path path) throws IOException;
}
