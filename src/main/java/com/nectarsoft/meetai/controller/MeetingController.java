package com.nectarsoft.meetai.controller;

import com.nectarsoft.meetai.core.exception.Exceptions;
import com.nectarsoft.meetai.dto.MeetingDetailResponse;
import com.nectarsoft.meetai.model.Meeting;
import com.nectarsoft.meetai.repository.MeetingRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Meetings", description = "회의 결과 조회")
@RestController
@RequestMapping("/api/v1/meetings")
@RequiredArgsConstructor
public class MeetingController {

    private final MeetingRepository meetingRepo;

    @Operation(summary = "회의 결과 상세 조회 (STT 세그먼트 포함)")
    @GetMapping("/{meetingId}")
    public MeetingDetailResponse getMeeting(@PathVariable String meetingId) {
        Meeting m = meetingRepo.findById(meetingId)
                .orElseThrow(() -> new Exceptions.MeetingNotFoundError(meetingId));
        return MeetingDetailResponse.from(m);
    }
}
