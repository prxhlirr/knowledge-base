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
 * P2-1 修复：导航守卫，防止未授权用户直接访问 /admin 子路由。
 * 检查 localStorage.admin_token 是否存在；未登录时重定向到首页并附带 redirect 参数。
 * 注意：前端 token 仅作 UI 隔离，核心授权由后端 X-Internal-Token 保证。
 */
const ADMIN_PREFIX = '/admin'
router.beforeEach((to, from, next) => {
    if (to.path.startsWith(ADMIN_PREFIX)) {
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
