package com.nectarsoft.meetai.controller;

import com.nectarsoft.meetai.core.exception.Exceptions;
import com.nectarsoft.meetai.dto.MeetingDetailResponse;
import com.nectarsoft.meetai.model.Meeting;
import com.nectarsoft.meetai.model.Transcript;
import com.nectarsoft.meetai.repository.MeetingRepository;
import com.nectarsoft.meetai.repository.TranscriptRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(name = "Meetings", description = "회의 결과 조회")
@RestController
@RequestMapping("/api/v1/meetings")
@RequiredArgsConstructor
public class MeetingController {

    private final MeetingRepository meetingRepo;
    private final TranscriptRepository transcriptRepo;

    @Operation(summary = "회의 결과 상세 조회 (대화록 포함)")
    @GetMapping("/{meetingId}")
    public MeetingDetailResponse getMeeting(@PathVariable UUID meetingId) {
        Meeting meeting = meetingRepo.findById(meetingId)
                .orElseThrow(() -> new Exceptions.MeetingNotFoundError(meetingId.toString()));
        List<Transcript> transcripts = transcriptRepo.findByMeetingMeetingIdOrderByStartSecAsc(meetingId);
        return MeetingDetailResponse.from(meeting, transcripts);
    }
}
