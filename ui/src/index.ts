import { definePlugin, type PluginModule, type RouteRecordAppend } from '@halo-dev/ui-shared'
import { RouterView, type RouteRecordRaw } from 'vue-router'
import IconGrid from '~icons/mingcute/layout-grid-line'
import { markRaw } from 'vue'

// Halo setupModules supports both entries in one list; ui-shared 2.26 types it as a union of arrays.
const routes: (RouteRecordRaw | RouteRecordAppend)[] = [
  {
    parentName: 'Root',
    route: {
      path: '/ai-page-builder',
      name: 'AiPageBuilderRoot',
      component: RouterView,
      meta: {
        title: 'AI 页面构建',
        searchable: true,
        permissions: ['*'],
        menu: { name: 'AI 页面构建', group: 'content', icon: markRaw(IconGrid), priority: 30 },
      },
      children: [
        {
          path: '',
          name: 'AiPageBuilderPages',
          component: () => import('./views/HomeView.vue'),
        },
        {
          path: 'new',
          name: 'AiPageBuilderNew',
          component: () => import('./views/CreatePageView.vue'),
          meta: { title: '新建页面', permissions: ['*'] },
        },
      ],
    },
  },
  {
    path: '/ai-page-builder/:name',
    name: 'AiPageBuilderEditor',
    component: () => import('./views/EditorView.vue'),
    meta: { title: '页面工作台', permissions: ['*'] },
  },
]
export default definePlugin({
  components: {},
  routes: routes as PluginModule['routes'],
  extensionPoints: {},
})
