package com.ruoyi.web.controller.course;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Course admin menu and permission definitions.
 */
public final class CourseAdminPermissions
{
    public static final long MENU_ROOT = 2000L;
    public static final long MENU_MANAGE = 2001L;

    public static final String PERM_MANAGE_VIEW = "course:manage:view";
    public static final String PERM_SETTINGS_VIEW = "course:settings:view";
    public static final String PERM_SETTINGS_EDIT = "course:settings:edit";
    public static final String PERM_COURSES_LIST = "course:courses:list";
    public static final String PERM_COURSES_ADD = "course:courses:add";
    public static final String PERM_COURSES_EDIT = "course:courses:edit";
    public static final String PERM_COURSES_REMOVE = "course:courses:remove";
    public static final String PERM_DOCS_LIST = "course:docs:list";
    public static final String PERM_DOCS_ADD = "course:docs:add";
    public static final String PERM_DOCS_EDIT = "course:docs:edit";
    public static final String PERM_DOCS_REMOVE = "course:docs:remove";
    public static final String PERM_QUESTIONS_LIST = "course:questions:list";
    public static final String PERM_QUESTIONS_ADD = "course:questions:add";
    public static final String PERM_QUESTIONS_EDIT = "course:questions:edit";
    public static final String PERM_QUESTIONS_REMOVE = "course:questions:remove";
    public static final String PERM_ORDERS_LIST = "course:orders:list";
    public static final String PERM_ORDERS_ADD = "course:orders:add";
    public static final String PERM_ORDERS_EDIT = "course:orders:edit";
    public static final String PERM_ORDERS_CLOSE = "course:orders:close";
    public static final String PERM_AUTH_LIST = "course:auth:list";
    public static final String PERM_AUTH_EDIT = "course:auth:edit";
    public static final String PERM_USERS_LIST = "course:users:list";
    public static final String PERM_USERS_EDIT = "course:users:edit";
    public static final String PERM_CODES_LIST = "course:codes:list";
    public static final String PERM_CODES_ADD = "course:codes:add";
    public static final String PERM_CODES_EDIT = "course:codes:edit";
    public static final String PERM_CODES_REMOVE = "course:codes:remove";
    public static final String PERM_CODES_CLOSE = "course:codes:close";
    public static final String PERM_AGENCIES_LIST = "course:agencies:list";
    public static final String PERM_AGENCIES_EDIT = "course:agencies:edit";
    public static final String PERM_STUDY_LIST = "course:study:list";
    public static final String PERM_RATINGS_LIST = "course:ratings:list";
    public static final String PERM_VOCABULARY_VIEW = "course:vocabulary:view";
    public static final String PERM_SUB_ACCOUNTS_LIST = "course:subAccounts:list";
    public static final String PERM_SUB_ACCOUNTS_ADD = "course:subAccounts:add";
    public static final String PERM_SUB_ACCOUNTS_EDIT = "course:subAccounts:edit";
    public static final String PERM_SUB_ACCOUNTS_REMOVE = "course:subAccounts:remove";

    public static final String ANY_ACCESS = PERM_MANAGE_VIEW + ","
        + PERM_SETTINGS_VIEW + "," + PERM_SETTINGS_EDIT + ","
        + PERM_COURSES_LIST + "," + PERM_COURSES_ADD + "," + PERM_COURSES_EDIT + "," + PERM_COURSES_REMOVE + ","
        + PERM_DOCS_LIST + "," + PERM_DOCS_ADD + "," + PERM_DOCS_EDIT + "," + PERM_DOCS_REMOVE + ","
        + PERM_QUESTIONS_LIST + "," + PERM_QUESTIONS_ADD + "," + PERM_QUESTIONS_EDIT + "," + PERM_QUESTIONS_REMOVE + ","
        + PERM_ORDERS_LIST + "," + PERM_ORDERS_ADD + "," + PERM_ORDERS_EDIT + "," + PERM_ORDERS_CLOSE + ","
        + PERM_AUTH_LIST + "," + PERM_AUTH_EDIT + ","
        + PERM_USERS_LIST + "," + PERM_USERS_EDIT + ","
        + PERM_CODES_LIST + "," + PERM_CODES_ADD + "," + PERM_CODES_EDIT + "," + PERM_CODES_REMOVE + "," + PERM_CODES_CLOSE + ","
        + PERM_AGENCIES_LIST + "," + PERM_AGENCIES_EDIT + ","
        + PERM_STUDY_LIST + "," + PERM_RATINGS_LIST + "," + PERM_VOCABULARY_VIEW;

    public static final String COURSE_OPTIONS_ACCESS = ANY_ACCESS;
    public static final String USER_OPTIONS_ACCESS = PERM_USERS_LIST + "," + PERM_USERS_EDIT + ","
        + PERM_ORDERS_LIST + "," + PERM_ORDERS_ADD + "," + PERM_CODES_LIST + "," + PERM_CODES_ADD + ","
        + PERM_AGENCIES_LIST + "," + PERM_AGENCIES_EDIT;

    private static final List<MenuDefinition> MENU_DEFINITIONS;
    private static final List<ModuleDefinition> MODULE_DEFINITIONS;
    private static final Map<String, ModuleDefinition> MODULE_MAP;

    static
    {
        List<MenuDefinition> menus = new ArrayList<>();
        menus.add(new MenuDefinition(MENU_ROOT, 0L, "课程后台", 1, "course", null, "", "Course", "M", "0", "", "education"));
        menus.add(new MenuDefinition(MENU_MANAGE, MENU_ROOT, "课程上传与用户", 1, "manage", "course/manage/index", "", "CourseManage", "C", "0", PERM_MANAGE_VIEW, "education"));

        List<ModuleDefinition> modules = new ArrayList<>();
        modules.add(module("frontend", "前端配置", "首页轮播图、隐私政策、用户协议", 2010L, PERM_SETTINGS_VIEW, PERM_SETTINGS_EDIT));
        modules.add(module("courses", "课程管理", "课程上传、章节、视频与课程状态", 2020L, PERM_COURSES_LIST, PERM_COURSES_ADD, PERM_COURSES_EDIT, PERM_COURSES_REMOVE));
        modules.add(module("docs", "资料管理", "课程资料新增、编辑与删除", 2030L, PERM_DOCS_LIST, PERM_DOCS_ADD, PERM_DOCS_EDIT, PERM_DOCS_REMOVE));
        modules.add(module("questions", "题库管理", "题库新增、编辑、绑定与删除", 2040L, PERM_QUESTIONS_LIST, PERM_QUESTIONS_ADD, PERM_QUESTIONS_EDIT, PERM_QUESTIONS_REMOVE));
        modules.add(module("orders", "授权开通", "手动开课、授权申请、开通记录", 2050L, PERM_ORDERS_LIST, PERM_ORDERS_ADD, PERM_ORDERS_EDIT, PERM_ORDERS_CLOSE, PERM_AUTH_LIST, PERM_AUTH_EDIT));
        modules.add(module("users", "用户管理", "学员、机构账号与用户资料维护", 2060L, PERM_USERS_LIST, PERM_USERS_EDIT));
        modules.add(module("codes", "激活码管理", "激活码生成、分配、锁定和关闭授权", 2070L, PERM_CODES_LIST, PERM_CODES_ADD, PERM_CODES_EDIT, PERM_CODES_REMOVE, PERM_CODES_CLOSE));
        modules.add(module("agencies", "校区管理", "分机构统计、名额和明细查看", 2080L, PERM_AGENCIES_LIST, PERM_AGENCIES_EDIT));
        modules.add(module("study", "后台记录查看", "后台操作记录、课时评价、AI 问答", 2090L, PERM_STUDY_LIST));
        modules.add(module("ratings", "课程打分统计", "正式课评分与评价统计", 2100L, PERM_RATINGS_LIST));
        modules.add(module("vocabulary", "外语词汇系统", "外语词汇预留模块", 2110L, PERM_VOCABULARY_VIEW));
        modules.add(module("subAccounts", "后台小号管理", "创建小号并授权可操作板块", 2120L,
            PERM_SUB_ACCOUNTS_LIST, PERM_SUB_ACCOUNTS_ADD, PERM_SUB_ACCOUNTS_EDIT, PERM_SUB_ACCOUNTS_REMOVE));

        for (ModuleDefinition module : modules)
        {
            int index = 0;
            for (String permission : module.getPermissions())
            {
                menus.add(new MenuDefinition(
                    module.getBaseMenuId() + index,
                    MENU_MANAGE,
                    module.getLabel() + permissionSuffix(permission),
                    (int) module.getBaseMenuId(),
                    "",
                    "",
                    "",
                    "",
                    "F",
                    "0",
                    permission,
                    "#"
                ));
                index++;
            }
        }

        MENU_DEFINITIONS = Collections.unmodifiableList(menus);
        MODULE_DEFINITIONS = Collections.unmodifiableList(modules);
        Map<String, ModuleDefinition> moduleMap = new LinkedHashMap<>();
        for (ModuleDefinition module : modules)
        {
            moduleMap.put(module.getKey(), module);
        }
        MODULE_MAP = Collections.unmodifiableMap(moduleMap);
    }

    private CourseAdminPermissions()
    {
    }

    public static List<MenuDefinition> menus()
    {
        return MENU_DEFINITIONS;
    }

    public static List<ModuleDefinition> modules()
    {
        return MODULE_DEFINITIONS;
    }

    public static Set<String> permissionsForModules(List<String> moduleKeys)
    {
        Set<String> permissions = new LinkedHashSet<>();
        if (moduleKeys == null)
        {
            return permissions;
        }
        for (String key : moduleKeys)
        {
            ModuleDefinition module = MODULE_MAP.get(key);
            if (module != null && !"subAccounts".equals(module.getKey()))
            {
                permissions.addAll(module.getPermissions());
            }
        }
        return permissions;
    }

    public static Long[] menuIdsForModules(List<String> moduleKeys)
    {
        Set<Long> ids = new LinkedHashSet<>();
        Set<String> permissions = permissionsForModules(moduleKeys);
        if (!permissions.isEmpty())
        {
            ids.add(MENU_ROOT);
            ids.add(MENU_MANAGE);
        }
        for (String permission : permissions)
        {
            for (MenuDefinition menu : MENU_DEFINITIONS)
            {
                if (permission.equals(menu.getPerms()))
                {
                    ids.add(menu.getMenuId());
                }
            }
        }
        return ids.toArray(new Long[0]);
    }

    public static List<String> moduleKeysForPermissions(Set<String> permissions)
    {
        List<String> keys = new ArrayList<>();
        if (permissions == null)
        {
            return keys;
        }
        for (ModuleDefinition module : MODULE_DEFINITIONS)
        {
            if ("subAccounts".equals(module.getKey()))
            {
                continue;
            }
            for (String permission : module.getPermissions())
            {
                if (permissions.contains(permission))
                {
                    keys.add(module.getKey());
                    break;
                }
            }
        }
        return keys;
    }

    private static ModuleDefinition module(String key, String label, String description, long baseMenuId, String... permissions)
    {
        return new ModuleDefinition(key, label, description, baseMenuId, Arrays.asList(permissions));
    }

    private static String permissionSuffix(String permission)
    {
        int index = permission.lastIndexOf(':');
        return index >= 0 ? "-" + permission.substring(index + 1) : "";
    }

    public static class MenuDefinition
    {
        private final long menuId;
        private final long parentId;
        private final String menuName;
        private final int orderNum;
        private final String path;
        private final String component;
        private final String query;
        private final String routeName;
        private final String menuType;
        private final String visible;
        private final String perms;
        private final String icon;

        public MenuDefinition(long menuId, long parentId, String menuName, int orderNum, String path, String component,
            String query, String routeName, String menuType, String visible, String perms, String icon)
        {
            this.menuId = menuId;
            this.parentId = parentId;
            this.menuName = menuName;
            this.orderNum = orderNum;
            this.path = path;
            this.component = component;
            this.query = query;
            this.routeName = routeName;
            this.menuType = menuType;
            this.visible = visible;
            this.perms = perms;
            this.icon = icon;
        }

        public long getMenuId()
        {
            return menuId;
        }

        public long getParentId()
        {
            return parentId;
        }

        public String getMenuName()
        {
            return menuName;
        }

        public int getOrderNum()
        {
            return orderNum;
        }

        public String getPath()
        {
            return path;
        }

        public String getComponent()
        {
            return component;
        }

        public String getQuery()
        {
            return query;
        }

        public String getRouteName()
        {
            return routeName;
        }

        public String getMenuType()
        {
            return menuType;
        }

        public String getVisible()
        {
            return visible;
        }

        public String getPerms()
        {
            return perms;
        }

        public String getIcon()
        {
            return icon;
        }
    }

    public static class ModuleDefinition
    {
        private final String key;
        private final String label;
        private final String description;
        private final long baseMenuId;
        private final List<String> permissions;

        public ModuleDefinition(String key, String label, String description, long baseMenuId, List<String> permissions)
        {
            this.key = key;
            this.label = label;
            this.description = description;
            this.baseMenuId = baseMenuId;
            this.permissions = Collections.unmodifiableList(new ArrayList<>(permissions));
        }

        public String getKey()
        {
            return key;
        }

        public String getLabel()
        {
            return label;
        }

        public String getDescription()
        {
            return description;
        }

        public long getBaseMenuId()
        {
            return baseMenuId;
        }

        public List<String> getPermissions()
        {
            return permissions;
        }
    }
}
