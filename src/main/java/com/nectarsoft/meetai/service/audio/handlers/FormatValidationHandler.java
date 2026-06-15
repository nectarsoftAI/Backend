package com.nectarsoft.meetai.service.audio.handlers;

import com.nectarsoft.meetai.config.MeetAiProperties;
import com.nectarsoft.meetai.core.exception.Exceptions;
import com.nectarsoft.meetai.service.audio.AudioContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class FormatValidationHandler extends AudioHandler {

    private final MeetAiProperties props;

    @Override
    public AudioContext handle(AudioContext ctx) {
        String filename = ctx.getOriginalPath().getFileName().toString().toLowerCase();
        boolean supported = props.getAudio().getSupportedFormatList()
                .stream()
                .anyMatch(ext -> filename.endsWith("." + ext));

        if (!supported) {
            throw new Exceptions.AudioFormatError(filename);
        }
        log.debug("[FormatValidation] OK — {}", filename);
        return passToNext(ctx);
    }
}
