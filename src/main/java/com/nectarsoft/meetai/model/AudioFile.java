package com.nectarsoft.meetai.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "audio_files")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class AudioFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "audio_id")
    private Long audioId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "meeting_id", nullable = false)
    private Meeting meeting;

    @Enumerated(EnumType.STRING)
    @Column(name = "storage_type", nullable = false)
    private StorageType storageType;

    @Column(name = "storage_path", length = 1024)
    private String storagePath;

    @Column(name = "original_filename", length = 255)
    private String originalFilename;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "sample_rate")
    private Integer sampleRate;
}
