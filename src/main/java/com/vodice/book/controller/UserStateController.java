package com.vodice.book.controller;

import com.vodice.book.model.dto.UserState;
import com.vodice.book.security.SecurityUtil;
import com.vodice.book.service.UserStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/user/state")
@RequiredArgsConstructor
public class UserStateController {

    private final UserStateService userStateService;

    @GetMapping
    public ResponseEntity<UserState> getState() {
        Long userId = SecurityUtil.getCurrentUserId();
        UserState state = userStateService.getState(userId);
        if (state == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(state);
    }

    @PostMapping
    public ResponseEntity<Void> saveState(@RequestBody UserState state) {
        Long userId = SecurityUtil.getCurrentUserId();
        userStateService.saveState(userId, state);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> clearState() {
        Long userId = SecurityUtil.getCurrentUserId();
        userStateService.clearState(userId);
        return ResponseEntity.ok().build();
    }
}
