package com.repopilot.service;

import com.repopilot.entity.UserEntity;
import com.repopilot.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * UserService 单元测试
 * 
 * 测试用例覆盖:
 * - TC-001 ~ TC-005: 用户认证相关功能
 * - TC-016: 用户数据隔离
 * - TC-017: 管理员权限功能
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepo;

    @Mock
    private JdbcTemplate jdbc;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepo, jdbc);
    }

    // ========== GitHub OAuth 用户创建/查找测试 ==========

    @Test
    void findOrCreateByGithubLogin_newUser_shouldCreateUser() {
        when(userRepo.findByUsername("githubuser")).thenReturn(Optional.empty());
        when(userRepo.save(any(UserEntity.class))).thenAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            user.setId(1L);
            return user;
        });

        UserEntity result = userService.findOrCreateByGithubLogin("githubuser", "https://avatar.url");

        assertThat(result).isNotNull();
        assertThat(result.getUsername()).isEqualTo("githubuser");
        assertThat(result.getGithubLogin()).isEqualTo("githubuser");
        assertThat(result.getAvatarUrl()).isEqualTo("https://avatar.url");
        assertThat(result.getRole()).isEqualTo("user");
        assertThat(result.getStatus()).isEqualTo("active");
        verify(userRepo).save(any(UserEntity.class));
    }

    @Test
    void findOrCreateByGithubLogin_existingUser_shouldUpdateAndReturn() {
        UserEntity existingUser = new UserEntity();
        existingUser.setId(1L);
        existingUser.setUsername("githubuser");
        existingUser.setGithubLogin("githubuser");
        existingUser.setAvatarUrl("https://old.avatar.url");
        existingUser.setStatus("active");

        when(userRepo.findByUsername("githubuser")).thenReturn(Optional.of(existingUser));
        when(userRepo.save(any(UserEntity.class))).thenReturn(existingUser);

        UserEntity result = userService.findOrCreateByGithubLogin("githubuser", "https://new.avatar.url");

        assertThat(result).isEqualTo(existingUser);
        assertThat(result.getAvatarUrl()).isEqualTo("https://new.avatar.url");
        verify(userRepo).save(existingUser);
    }

    @Test
    void findOrCreateByGithubLogin_disabledUser_shouldThrowException() {
        UserEntity disabledUser = new UserEntity();
        disabledUser.setId(1L);
        disabledUser.setUsername("disableduser");
        disabledUser.setStatus("disabled");

        when(userRepo.findByUsername("disableduser")).thenReturn(Optional.of(disabledUser));
        when(userRepo.save(any(UserEntity.class))).thenReturn(disabledUser);

        assertThatThrownBy(() -> userService.findOrCreateByGithubLogin("disableduser", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已被管理员禁用");
    }

    @Test
    void findOrCreateByGithubLogin_withWhitespace_shouldTrim() {
        when(userRepo.findByUsername("githubuser")).thenReturn(Optional.empty());
        when(userRepo.save(any(UserEntity.class))).thenAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            user.setId(1L);
            return user;
        });

        UserEntity result = userService.findOrCreateByGithubLogin("  githubuser  ", null);

        assertThat(result.getUsername()).isEqualTo("githubuser");
        verify(userRepo).findByUsername("githubuser");
    }

    // ========== 管理员登录测试 ==========

    // Helper method to compute SHA-256 hash (same as UserService.hash)
    private String computeHash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void login_validCredentials_shouldReturnToken() {
        String passwordHash = computeHash("admin123");
        UserEntity user = new UserEntity();
        user.setId(1L);
        user.setUsername("admin");
        user.setPasswordHash(passwordHash);
        user.setRole("admin");
        user.setStatus("active");

        when(userRepo.findByUsername("admin")).thenReturn(Optional.of(user));
        when(userRepo.save(any(UserEntity.class))).thenReturn(user);

        var result = userService.login("admin", "admin123");

        assertThat(result).containsKey("token");
        assertThat(result.get("username")).isEqualTo("admin");
        assertThat(result.get("role")).isEqualTo("admin");
    }

    @Test
    void login_invalidPassword_shouldThrowException() {
        String passwordHash = computeHash("correctpassword");
        UserEntity user = new UserEntity();
        user.setId(1L);
        user.setUsername("admin");
        user.setPasswordHash(passwordHash);
        user.setStatus("active");

        when(userRepo.findByUsername("admin")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.login("admin", "wrongpassword"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("用户名或密码错误");
    }

    @Test
    void login_nonexistentUser_shouldThrowException() {
        when(userRepo.findByUsername("nonexistent")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.login("nonexistent", "anypassword"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("用户名或密码错误");
    }

    @Test
    void login_disabledUser_shouldThrowException() {
        String passwordHash = computeHash("password");
        UserEntity user = new UserEntity();
        user.setId(1L);
        user.setUsername("disabled");
        user.setPasswordHash(passwordHash);
        user.setStatus("disabled");

        when(userRepo.findByUsername("disabled")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.login("disabled", "password"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已被禁用");
    }

    // ========== 用户封禁/解禁测试 ==========

    @Test
    void banUser_normalUser_shouldSetStatusToDisabled() {
        UserEntity user = new UserEntity();
        user.setId(1L);
        user.setUsername("testuser");
        user.setRole("user");
        user.setStatus("active");

        when(userRepo.findById(1L)).thenReturn(Optional.of(user));
        when(userRepo.save(any(UserEntity.class))).thenReturn(user);

        var result = userService.banUser(1L);

        assertThat(result.get("status")).isEqualTo("disabled");
        verify(userRepo).save(user);
    }

    @Test
    void banUser_adminUser_shouldThrowException() {
        UserEntity admin = new UserEntity();
        admin.setId(1L);
        admin.setUsername("admin");
        admin.setRole("admin");

        when(userRepo.findById(1L)).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> userService.banUser(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能封禁管理员");
    }

    @Test
    void unbanUser_disabledUser_shouldSetStatusToActive() {
        UserEntity user = new UserEntity();
        user.setId(1L);
        user.setUsername("testuser");
        user.setStatus("disabled");

        when(userRepo.findById(1L)).thenReturn(Optional.of(user));
        when(userRepo.save(any(UserEntity.class))).thenReturn(user);

        var result = userService.unbanUser(1L);

        assertThat(result.get("status")).isEqualTo("active");
    }

    // ========== 创建用户测试 ==========

    @Test
    void createUser_validData_shouldCreateUser() {
        when(userRepo.existsByUsername("newuser")).thenReturn(false);
        when(userRepo.save(any(UserEntity.class))).thenAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            user.setId(1L);
            return user;
        });

        var result = userService.createUser("newuser", "password123", "test@example.com", "user");

        assertThat(result.get("username")).isEqualTo("newuser");
        assertThat(result.get("email")).isEqualTo("test@example.com");
        assertThat(result.get("role")).isEqualTo("user");
        assertThat(result.get("status")).isEqualTo("active");
    }

    @Test
    void createUser_existingUsername_shouldThrowException() {
        when(userRepo.existsByUsername("existinguser")).thenReturn(true);

        assertThatThrownBy(() -> userService.createUser("existinguser", "password123", null, "user"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("用户名已存在");
    }

    @Test
    void createUser_shortPassword_shouldThrowException() {
        assertThatThrownBy(() -> userService.createUser("newuser", "12345", null, "user"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("密码至少 6 位");
    }

    @Test
    void createUser_emptyUsername_shouldThrowException() {
        assertThatThrownBy(() -> userService.createUser("", "password123", null, "user"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("用户名不能为空");
    }

    // ========== 数据隔离测试 ==========

    @Test
    void listAll_withMultipleUsers_shouldReturnAllUsers() {
        UserEntity user1 = new UserEntity();
        user1.setId(1L);
        user1.setUsername("user1");
        user1.setGithubLogin("user1");
        user1.setRole("user");
        user1.setStatus("active");

        UserEntity user2 = new UserEntity();
        user2.setId(2L);
        user2.setUsername("user2");
        user2.setGithubLogin("user2");
        user2.setRole("user");
        user2.setStatus("active");

        when(userRepo.findAll()).thenReturn(java.util.List.of(user1, user2));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(0);

        var result = userService.listAll();

        assertThat(result).hasSize(2);
    }
}
