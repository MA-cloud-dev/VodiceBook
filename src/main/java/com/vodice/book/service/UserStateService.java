package com.vodice.book.service;

import com.vodice.book.model.dto.UserState;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class UserStateService {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String KEY_PREFIX = "user:state:";
    // 保存24小时
    private static final long EXPIRE_HOURS = 24L;

    public void saveState(Long userId, UserState state) {
        String key = KEY_PREFIX + userId;
        redisTemplate.opsForValue().set(key, state, EXPIRE_HOURS, TimeUnit.HOURS);
    }

    public UserState getState(Long userId) {
        String key = KEY_PREFIX + userId;
        Object obj = redisTemplate.opsForValue().get(key);
        if (obj instanceof UserState) {
            return (UserState) obj;
        }
        return null;
    }

    public void clearState(Long userId) {
        String key = KEY_PREFIX + userId;
        redisTemplate.delete(key);
    }
}
