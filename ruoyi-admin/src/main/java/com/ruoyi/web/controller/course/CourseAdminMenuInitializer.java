package com.ruoyi.web.controller.course;

import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.ruoyi.common.core.domain.entity.SysMenu;
import com.ruoyi.system.service.ISysMenuService;

/**
 * Keeps the course admin route and button permissions available in sys_menu.
 */
@Component
public class CourseAdminMenuInitializer
{
    @Autowired
    private ISysMenuService menuService;

    @PostConstruct
    public void initCourseAdminMenus()
    {
        for (CourseAdminPermissions.MenuDefinition definition : CourseAdminPermissions.menus())
        {
            SysMenu menu = toSysMenu(definition);
            if (menuService.selectMenuById(menu.getMenuId()) == null)
            {
                menu.setCreateBy("system");
                menuService.insertMenu(menu);
            }
            else
            {
                menu.setUpdateBy("system");
                menuService.updateMenu(menu);
            }
        }
    }

    private SysMenu toSysMenu(CourseAdminPermissions.MenuDefinition definition)
    {
        SysMenu menu = new SysMenu();
        menu.setMenuId(definition.getMenuId());
        menu.setParentId(definition.getParentId());
        menu.setMenuName(definition.getMenuName());
        menu.setOrderNum(definition.getOrderNum());
        menu.setPath(definition.getPath());
        menu.setComponent(definition.getComponent());
        menu.setQuery(definition.getQuery());
        menu.setRouteName(definition.getRouteName());
        menu.setIsFrame("1");
        menu.setIsCache("0");
        menu.setMenuType(definition.getMenuType());
        menu.setVisible(definition.getVisible());
        menu.setStatus("0");
        menu.setPerms(definition.getPerms());
        menu.setIcon(definition.getIcon());
        return menu;
    }
}
