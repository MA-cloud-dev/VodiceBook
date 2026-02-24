package com.vodice.book.config;

import com.vodice.book.model.User;
import com.vodice.book.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 应用启动时初始化测试账号
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.test-user.username}")
    private String testUsername;

    @Value("${app.test-user.password}")
    private String testPassword;

    @Override
    public void run(String... args) {
        if (!userRepository.existsByUsername(testUsername)) {
            User user = new User();
            user.setUsername(testUsername);
            user.setPassword(passwordEncoder.encode(testPassword));
            user.setNickname("管理员");
            userRepository.save(user);
            log.info("测试账号已创建: {}", testUsername);
        } else {
            log.info("测试账号已存在: {}", testUsername);
        }
    }
}
