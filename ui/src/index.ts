import { definePlugin, type PluginModule, type RouteRecordAppend } from '@halo-dev/ui-shared'
import { RouterView, type RouteRecordRaw } from 'vue-router'
import IconFileAi from '~icons/tabler/file-ai'
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
        title: '页面工坊',
        permissions: ['*'],
        menu: { name: '页面工坊', group: 'content', icon: markRaw(IconFileAi), priority: 30 },
      },
      children: [
        {
          path: '',
          name: 'AiPageBuilderPages',
          component: () => import('./views/HomeView.vue'),
          // Global search pushes the whole route record, which vue-router
          // resolves by name, so searchable must live on this leaf route;
          // putting it on the RouterView parent would render an empty outlet.
          meta: { title: '页面工坊', searchable: true, permissions: ['*'] },
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
