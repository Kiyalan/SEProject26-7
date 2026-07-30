package com.repopilot.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private UserService userService;

    private AdminService adminService;

    @BeforeEach
    void setUp() {
        adminService = new AdminService(jdbcTemplate, userService);
        // Default stub for all query calls to return empty list
        lenient().when(jdbcTemplate.query(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.<Object[]>any(),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper>any()
        )).thenReturn(List.of());
        lenient().when(jdbcTemplate.query(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper>any()
        )).thenReturn(List.of());
    }

    @Test
    void login_withValidAdmin_returnsToken() {
        when(userService.login("admin", "admin123"))
                .thenReturn(Map.of("username", "admin", "role", "admin", "userId", 1L));

        var result = adminService.login("admin", "admin123");

        assertThat(result.get("username")).isEqualTo("admin");
        assertThat(result.get("role")).isEqualTo("admin");
        assertThat(result.get("token")).isNotNull();
        assertThat(((String) result.get("token")).startsWith("admin.")).isTrue();
    }

    @Test
    void login_withInvalidCredentials_throws() {
        when(userService.login("admin", "wrongpassword"))
                .thenThrow(new IllegalArgumentException("用户名或密码错误"));

        assertThatThrownBy(() -> adminService.login("admin", "wrongpassword"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("用户名或密码错误");
    }

    @Test
    void login_withNonAdmin_throws() {
        when(userService.login("user", "pass"))
                .thenReturn(Map.of("username", "user", "role", "user", "userId", 2L));

        assertThatThrownBy(() -> adminService.login("user", "pass"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("无管理员权限");
    }

    @Test
    void login_withNullCredentials_throws() {
        assertThatThrownBy(() -> adminService.login(null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("账号或密码错误");
    }

    @Test
    void requireAdmin_withValidToken_returnsUsername() {
        // First login to create a valid session
        when(userService.login("admin", "admin123"))
                .thenReturn(Map.of("username", "admin", "role", "admin", "userId", 1L));

        var loginResult = adminService.login("admin", "admin123");
        // Token format is "admin." + UUID without dashes
        String token = (String) loginResult.get("token");

        String admin = adminService.requireAdmin("Bearer " + token);

        assertThat(admin).isEqualTo("admin");
    }

    @Test
    void requireAdmin_withInvalidToken_throws() {
        assertThatThrownBy(() -> adminService.requireAdmin("Bearer invalid.token.here"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("管理员会话无效或已过期，请重新登录");
    }

    @Test
    void requireAdmin_withNullAuth_throws() {
        assertThatThrownBy(() -> adminService.requireAdmin(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("需要管理员登录");
    }

    @Test
    void requireAdmin_withMalformedAuth_throws() {
        assertThatThrownBy(() -> adminService.requireAdmin("NotBearer token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("需要管理员登录");
    }

    @Test
    void listSyncTasks_returnsEmptyList() {
        var result = adminService.listSyncTasks(null, null, 10);

        assertThat(result.get("items")).asList().isEmpty();
        assertThat(result.get("total")).isEqualTo(0);
    }

    @Test
    void listSyncTasks_withKeyword_returnsEmptyList() {
        var result = adminService.listSyncTasks(null, "test", 10);

        assertThat(result).isNotNull();
        assertThat(result).containsKey("items");
    }

    @Test
    void listSyncFailures_returnsEmptyList() {
        var result = adminService.listSyncFailures(10);

        assertThat(result.get("items")).asList().isEmpty();
    }

    @Test
    void integrity_returnsEmptyList() {
        var result = adminService.integrity(10);

        assertThat(result.get("items")).asList().isEmpty();
    }

    @Test
    void faqRepos_returnsEmptyList() {
        var result = adminService.faqRepos();

        assertThat(result.get("items")).asList().isEmpty();
    }

    @Test
    void auditLogs_returnsEmptyList() {
        var result = adminService.auditLogs(10);

        assertThat(result.get("items")).asList().isEmpty();
    }

    @Test
    void exportFaq_withEmptyRepoIds() {
        var result = adminService.exportFaq(List.of(), "markdown", "admin");

        assertThat(result.get("format")).isEqualTo("markdown");
        assertThat(result.get("itemCount")).isEqualTo(0);
        assertThat(result.get("repoCount")).isEqualTo(0);
    }

    @Test
    void exportFaq_withJsonFormat() {
        var result = adminService.exportFaq(List.of(), "json", "admin");

        assertThat(result.get("format")).isEqualTo("json");
    }

    @Test
    void exportFaq_withNullRepoIds() {
        var result = adminService.exportFaq(null, "markdown", "admin");

        assertThat(result.get("repoCount")).isEqualTo(0);
    }

    @Test
    void exportFaq_withUnknownFormat_defaultsToMarkdown() {
        var result = adminService.exportFaq(List.of(), "xml", "admin");

        assertThat(result.get("format")).isEqualTo("markdown");
    }

    @Test
    void users_delegatesToUserService() {
        when(userService.listAll()).thenReturn(List.of(
                Map.of("id", 1L, "username", "admin", "role", "admin")
        ));

        var result = adminService.users();

        assertThat(result.get("items")).asList().hasSize(1);
    }

    @Test
    void globalUserStats_delegatesToUserService() {
        when(userService.getGlobalUserStats()).thenReturn(Map.of(
                "totalUsers", 10,
                "activeUsers", 8
        ));

        var result = adminService.globalUserStats();

        assertThat(result.get("totalUsers")).isEqualTo(10);
    }

    @Test
    void mapTaskStatus_success() {
        var result = adminService.listSyncTasks("success", null, 10);

        assertThat(result).isNotNull();
        assertThat(result).containsKey("items");
    }

    @Test
    void mapTaskStatus_running() {
        var result = adminService.listSyncTasks("running", null, 10);

        assertThat(result).isNotNull();
        assertThat(result).containsKey("items");
    }

    @Test
    void mapTaskStatus_failed() {
        var result = adminService.listSyncTasks("failed", null, 10);

        assertThat(result).isNotNull();
        assertThat(result).containsKey("items");
    }

    @Test
    void mapTaskStatus_paused() {
        var result = adminService.listSyncTasks("paused", null, 10);

        assertThat(result).isNotNull();
        assertThat(result).containsKey("items");
    }

    @Test
    void createUser_delegatesToUserService() {
        when(userService.createUser("newuser", "pass123", "new@test.com", "user"))
                .thenReturn(Map.of("id", 3L, "username", "newuser"));

        var result = adminService.createUser("admin", "newuser", "pass123", "new@test.com", "user");

        assertThat(result.get("username")).isEqualTo("newuser");
    }

    @Test
    void updateUser_delegatesToUserService() {
        when(userService.updateUser(1L, "newpass", "updated@test.com", "admin", "active"))
                .thenReturn(Map.of("id", 1L, "username", "admin", "updated", true));

        var result = adminService.updateUser("admin", 1L, "newpass", "updated@test.com", "admin", "active");

        assertThat(result).isNotNull();
        assertThat(result).containsKey("updated");
    }

    @Test
    void banUser_delegatesToUserService() {
        when(userService.banUser(2L)).thenReturn(Map.of("id", 2L, "status", "banned"));

        var result = adminService.banUser("admin", 2L);

        assertThat(result.get("status")).isEqualTo("banned");
    }

    @Test
    void unbanUser_delegatesToUserService() {
        when(userService.unbanUser(2L)).thenReturn(Map.of("id", 2L, "status", "active"));

        var result = adminService.unbanUser("admin", 2L);

        assertThat(result.get("status")).isEqualTo("active");
    }
}
