import { createRouter, createWebHistory } from 'vue-router'
import Home from '../views/Home.vue'
import Admin from '../views/Admin.vue'
import ModelTuning from '../views/admin/ModelTuning.vue'
import DocImport from '../views/admin/DocImport.vue'

const routes = [
    {
        path: '/',
        name: 'Home',
        component: Home
    },
    {
        path: '/qa',
        name: 'QaChat',
        component: () => import('../views/QaChat.vue')
    },
    {
        path: '/editor',
        name: 'EditorWorkspace',
        component: () => import('../views/EditorWorkspace.vue')
    },

    {
        path: '/admin',
        name: 'Admin',
        component: Admin,
        children: [
            {
                path: 'tuning',
                name: 'ModelTuning',
                component: ModelTuning
            },
            {
                path: 'doc-import',
                name: 'DocImport',
                component: DocImport
            },
            {
                path: 'similarity',
                name: 'SimilarityLab',
                component: () => import('../views/admin/SimilarityLab.vue')
            },
            {
                path: 'tags',
                name: 'TagManagement',
                component: () => import('../views/admin/TagManagement.vue')
            },
            {
                path: 'logs',
                name: 'FileParseLog',
                component: () => import('../views/admin/FileParseLog.vue')
            },
            {
                path: 'version-log',
                name: 'DocVersionLog',
                component: () => import('../views/admin/DocVersionLog.vue')
            },
            {
                path: 'doc-management',
                name: 'DocManagement',
                component: () => import('../views/admin/DocManagement.vue')
            },
            {
                path: 'synonyms',
                name: 'SynonymManagement',
                component: () => import('../views/admin/SynonymManagement.vue')
            }
        ]
    }
]

const router = createRouter({
    history: createWebHistory(),
    routes
})

/**
 * 导航守卫：保护 /admin 子路由，防止未授权用户直接访问。
 * God Mode 说明：
 *   VITE_GOD_MODE=true 时跳过所有前端鉴权，适用于离线内网部署或开发调试场景。
 *   此时自动向 localStorage 写入 admin_token（值为 'god-mode'），保证页面内任何
 *   对 admin_token 的读取也能正常通过，无需任何登录操作。
 * 注意：前端 token 仅作 UI 隔离，核心接口授权由后端 X-Internal-Token / JWT 保证。
 */
const ADMIN_PREFIX = '/admin'
const GOD_MODE = import.meta.env.VITE_GOD_MODE === 'true'

// God Mode：启动时立即注入 token，保证首次导航和页面刷新都能通过守卫
if (GOD_MODE) {
    localStorage.setItem('admin_token', 'god-mode')
    console.warn('[GodMode] 上帝模式已启用，前端鉴权已完全绕过。生产环境请关闭！')
}

router.beforeEach((to, from, next) => {
    if (to.path.startsWith(ADMIN_PREFIX)) {
        // God Mode：直接放行，无需 token 检查
        if (GOD_MODE) {
            next()
            return
        }
        const token = localStorage.getItem('admin_token')
        if (!token) {
            // 未携带 token，重定向到首页，携带 redirect 参数便于登录后跳回
            next({ path: '/', query: { redirect: to.fullPath } })
            return
        }
    }
    next()
})

export default router
