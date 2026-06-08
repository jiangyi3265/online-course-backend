package com.ruoyi.web.controller.course;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.service.ISysMenuService;
import com.ruoyi.system.service.ISysRoleService;
import com.ruoyi.system.service.ISysUserService;

/**
 * Course admin sub-account management.
 */
@RestController
@RequestMapping("/course/admin/sub-accounts")
public class CourseSubAccountController extends BaseController
{
    private static final String SUB_ROLE_PREFIX = "course_sub_";
    private static final String SUB_ACCOUNT_REMARK = "COURSE_SUB_ACCOUNT";

    @Autowired
    private ISysUserService userService;

    @Autowired
    private ISysRoleService roleService;

    @Autowired
    private ISysMenuService menuService;

    @PreAuthorize("@ss.hasRole('admin')")
    @GetMapping("/permissions")
    public AjaxResult permissions()
    {
        List<Map<String, Object>> modules = new ArrayList<>();
        for (CourseAdminPermissions.ModuleDefinition module : CourseAdminPermissions.modules())
        {
            if ("subAccounts".equals(module.getKey()))
            {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", module.getKey());
            item.put("label", module.getLabel());
            item.put("description", module.getDescription());
            item.put("permissions", module.getPermissions());
            modules.add(item);
        }
        return AjaxResult.success(modules);
    }

    @PreAuthorize("@ss.hasRole('admin')")
    @GetMapping
    public AjaxResult list()
    {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SysUser user : userService.selectUserList(new SysUser()))
        {
            SysUser detail = userService.selectUserById(user.getUserId());
            SysRole role = findSubAccountRole(detail);
            if (role != null)
            {
                rows.add(toRow(detail, role));
            }
        }
        return AjaxResult.success(rows);
    }

    @PreAuthorize("@ss.hasRole('admin')")
    @PostMapping
    @Transactional
    public AjaxResult add(@RequestBody Map<String, Object> body)
    {
        String userName = str(body.get("userName")).trim();
        String nickName = str(body.get("nickName")).trim();
        String password = str(body.get("password")).trim();
        if (userName.length() == 0 || nickName.length() == 0 || password.length() == 0)
        {
            return AjaxResult.error("账号、名称和密码不能为空");
        }

        SysUser user = new SysUser();
        user.setDeptId(101L);
        user.setUserName(userName);
        user.setNickName(nickName);
        user.setPhonenumber(str(body.get("phonenumber")).trim());
        user.setEmail(str(body.get("email")).trim());
        user.setSex("2");
        user.setStatus(normalStatus(body.get("status")));
        user.setPassword(SecurityUtils.encryptPassword(password));
        user.setCreateBy(getUsername());
        user.setRemark(SUB_ACCOUNT_REMARK);
        if (!userService.checkUserNameUnique(user))
        {
            return AjaxResult.error("账号已存在");
        }
        if (StringUtils.isNotEmpty(user.getPhonenumber()) && !userService.checkPhoneUnique(user))
        {
            return AjaxResult.error("手机号已存在");
        }
        if (StringUtils.isNotEmpty(user.getEmail()) && !userService.checkEmailUnique(user))
        {
            return AjaxResult.error("邮箱已存在");
        }
        List<String> moduleKeys = moduleKeys(body);
        if (moduleKeys.isEmpty())
        {
            return AjaxResult.error("请至少选择一个可操作板块");
        }
        userService.insertUser(user);

        SysRole role = buildRoleForUser(user, moduleKeys);
        role.setCreateBy(getUsername());
        roleService.insertRole(role);
        userService.insertUserAuth(user.getUserId(), new Long[] { role.getRoleId() });
        return AjaxResult.success(toRow(userService.selectUserById(user.getUserId()), role));
    }

    @PreAuthorize("@ss.hasRole('admin')")
    @PutMapping("/{userId}")
    @Transactional
    public AjaxResult edit(@PathVariable Long userId, @RequestBody Map<String, Object> body)
    {
        SysUser user = assertSubAccount(userId);
        SysRole role = findSubAccountRole(user);

        user.setNickName(defaultIfBlank(str(body.get("nickName")).trim(), user.getNickName()));
        user.setPhonenumber(str(body.get("phonenumber")).trim());
        user.setEmail(str(body.get("email")).trim());
        user.setStatus(normalStatus(body.get("status")));
        user.setUpdateBy(getUsername());
        if (StringUtils.isNotEmpty(user.getPhonenumber()) && !userService.checkPhoneUnique(user))
        {
            return AjaxResult.error("手机号已存在");
        }
        if (StringUtils.isNotEmpty(user.getEmail()) && !userService.checkEmailUnique(user))
        {
            return AjaxResult.error("邮箱已存在");
        }
        String password = str(body.get("password")).trim();
        if (password.length() > 0)
        {
            user.setPassword(SecurityUtils.encryptPassword(password));
        }
        userService.updateUser(user);

        List<String> moduleKeys = moduleKeys(body);
        if (moduleKeys.isEmpty())
        {
            return AjaxResult.error("请至少选择一个可操作板块");
        }
        SysRole updatedRole = buildRoleForUser(user, moduleKeys);
        updatedRole.setRoleId(role.getRoleId());
        updatedRole.setUpdateBy(getUsername());
        roleService.updateRole(updatedRole);
        userService.insertUserAuth(user.getUserId(), new Long[] { role.getRoleId() });
        return AjaxResult.success(toRow(userService.selectUserById(userId), updatedRole));
    }

    @PreAuthorize("@ss.hasRole('admin')")
    @DeleteMapping("/{userId}")
    @Transactional
    public AjaxResult remove(@PathVariable Long userId)
    {
        SysUser user = assertSubAccount(userId);
        SysRole role = findSubAccountRole(user);
        userService.deleteUserById(userId);
        if (role != null)
        {
            roleService.deleteRoleById(role.getRoleId());
        }
        return AjaxResult.success();
    }

    private SysUser assertSubAccount(Long userId)
    {
        if (SecurityUtils.isAdmin(userId))
        {
            throw new ServiceException("不能操作总后台账号");
        }
        SysUser user = userService.selectUserById(userId);
        if (user == null || findSubAccountRole(user) == null)
        {
            throw new ServiceException("后台小号不存在");
        }
        return user;
    }

    private SysRole buildRoleForUser(SysUser user, List<String> moduleKeys)
    {
        SysRole role = new SysRole();
        role.setRoleName("后台小号-" + user.getUserName());
        role.setRoleKey(SUB_ROLE_PREFIX + user.getUserId());
        role.setRoleSort(20);
        role.setDataScope("1");
        role.setMenuCheckStrictly(true);
        role.setDeptCheckStrictly(true);
        role.setStatus("0");
        role.setRemark(SUB_ACCOUNT_REMARK);
        role.setMenuIds(CourseAdminPermissions.menuIdsForModules(moduleKeys));
        return role;
    }

    private Map<String, Object> toRow(SysUser user, SysRole role)
    {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("userId", user.getUserId());
        row.put("userName", user.getUserName());
        row.put("nickName", user.getNickName());
        row.put("phonenumber", user.getPhonenumber());
        row.put("email", user.getEmail());
        row.put("status", user.getStatus());
        row.put("createTime", user.getCreateTime());
        row.put("roleId", role.getRoleId());
        Set<String> permissions = menuService.selectMenuPermsByRoleId(role.getRoleId());
        row.put("permissionKeys", CourseAdminPermissions.moduleKeysForPermissions(permissions));
        row.put("permissions", permissions);
        return row;
    }

    private SysRole findSubAccountRole(SysUser user)
    {
        if (user == null || user.getRoles() == null)
        {
            return null;
        }
        for (SysRole role : user.getRoles())
        {
            if (role != null && role.getRoleKey() != null && role.getRoleKey().startsWith(SUB_ROLE_PREFIX))
            {
                return role;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<String> moduleKeys(Map<String, Object> body)
    {
        Object value = body.get("permissionKeys");
        if (!(value instanceof List))
        {
            return new ArrayList<>();
        }
        List<String> keys = new ArrayList<>();
        for (Object item : (List<Object>) value)
        {
            String key = str(item).trim();
            if (key.length() > 0)
            {
                keys.add(key);
            }
        }
        return keys;
    }

    private String normalStatus(Object value)
    {
        String status = str(value).trim();
        return "1".equals(status) || "disabled".equals(status) ? "1" : "0";
    }

    private String str(Object value)
    {
        return value == null ? "" : String.valueOf(value);
    }

    private String defaultIfBlank(String value, String fallback)
    {
        return value.length() == 0 ? fallback : value;
    }
}
