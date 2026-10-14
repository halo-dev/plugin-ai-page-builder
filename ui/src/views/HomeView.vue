<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useRouteQuery } from '@vueuse/router'
import { useMutation, useQuery } from '@tanstack/vue-query'
import {
  Dialog,
  Toast,
  VAlert,
  VButton,
  VDropdown,
  VDropdownItem,
  VEmpty,
  VLoading,
  VModal,
  VPageHeader,
  VPagination,
  VSpace,
  VStatusDot,
} from '@halo-dev/components'
import IconAddCircle from '~icons/mingcute/add-circle-line'
import IconGrid from '~icons/mingcute/layout-grid-line'
import IconMore from '~icons/mingcute/more-2-line'
import { pagePreviewUrl, aiPageBuilderApi } from '../api/clients'
import { errorMessage } from '../utils/error'
import { slugValidation } from '../utils/slug'
import { pageKeys, queryClient } from '../lib/query-client'
import type { PageSummary } from '../api/generated'
import ClonePageModal from '../components/ClonePageModal.vue'
import PagePreviewCard from '../components/PagePreviewCard.vue'

const router = useRouter()
const page = useRouteQuery<number>('page', 1, {
  transform: (value) => Math.max(1, Number(value) || 1),
})
const size = useRouteQuery<number>('size', 20, {
  transform: (value) => Math.max(1, Number(value) || 1),
})
const keyword = useRouteQuery<string>('keyword', '')
const listParams = computed(() => ({
  page: page.value,
  size: size.value,
  keyword: keyword.value,
}))
const listQuery = useQuery({
  queryKey: computed(() => pageKeys.list(listParams.value)),
  queryFn: async () => (await aiPageBuilderApi.listPages(listParams.value)).data,
  keepPreviousData: true,
  queryClient,
})
const items = computed(() => listQuery.data.value?.items || [])
const total = computed(() => listQuery.data.value?.total || 0)
const cloneTarget = ref<PageSummary>()
const createOpen = ref(false)
const form = ref({ title: '', slug: '', model: '', prompt: '', themeLayout: true })
const formNode = ref<{ submit: () => void }>()
const promptPresetGroups = [
  {
    label: '企业与品牌',
    presets: [
      {
        label: '公司介绍',
        prompt:
          '制作一个公司介绍独立页，包含公司简介、主营业务、服务对象、发展历程和联系入口。先参考站点资料、公开文章及已有关于页，使用真实品牌素材，沿用主题风格，不重复页头页脚。缺少关键信息时集中向我提问，不编造成立年份、规模、营收或行业地位。',
      },
      {
        label: '品牌故事',
        prompt:
          '制作一个品牌故事页，以创立缘起、关键节点、品牌理念和产品坚持串联内容。参考站内公开资料与附件，用真实故事和图片呈现；不编造创始人经历、采访引语或品牌历史，必要事实缺失时先问我。',
      },
      {
        label: '产品介绍',
        prompt:
          '制作一个产品介绍页。先明确产品和目标客户，再参考公开产品资料与附件，展示核心价值、典型场景、功能亮点、真实截图、规格及咨询或试用入口。沿用主题风格，不重复导航，不编造功能、性能参数、客户数量或购买链接；缺少必要资料先提问。',
      },
      {
        label: '产品系列',
        prompt:
          '制作一个产品系列展示页。根据已有产品资料按用途或系列分类，展示型号、图片、核心差异和真实详情链接，可加入本页静态筛选。产品清单或参数不明确时先问我，不虚构型号、库存或在线购买能力，也不承诺自动更新目录。',
      },
      {
        label: '行业解决方案',
        prompt:
          '制作一个面向特定行业的解决方案页。先确认行业、目标客户与核心需求，再结合站内产品和服务资料，组织业务痛点、适用方案、实施路径、交付内容和咨询入口。只引用有依据的案例与收益，不编造行业数据、合规结论或效果保证。',
      },
      {
        label: '客户案例',
        prompt:
          '制作一个客户案例展示页。先确认可公开的案例与素材，按客户背景、问题、方案、实施过程和实际成果展开，保留来源或详情链接。客户名称、Logo、评价和量化结果必须有资料支持；缺失时先问我，不编造客户背书或合作关系。',
      },
      {
        label: '团队介绍',
        prompt:
          '制作一个企业或小团队介绍页，展示团队定位、成员分工、专业背景、协作方式和联系入口。优先使用已有公开简介与照片；先确认要展示的成员，不编造姓名、头衔、履历或个人联系方式，不把附件中的人物照片擅自认定为团队成员。',
      },
      {
        label: '资质与荣誉',
        prompt:
          '制作一个资质与荣誉展示页，根据已有公开资料和证书附件，分类展示证书名称、颁发机构、获得时间、适用范围及可公开的证明链接。先确认素材可公开，不编造奖项、认证编号或有效期，不把过期资质描述为仍有效。',
      },
    ],
  },
  {
    label: '商务与服务',
    presets: [
      {
        label: '服务介绍',
        prompt:
          '制作一个企业或工作室的服务介绍页，展示服务对象、服务范围、交付物、合作流程、客户需要准备的资料和咨询入口。参考站内真实服务说明，突出适用场景；收费、周期、支持范围不清楚时先问我，不编造服务承诺。',
      },
      {
        label: '套餐与报价',
        prompt:
          '制作一个套餐与报价说明页。先确认实际套餐、币种、计费周期、含税情况、功能额度和额外费用，再用易比较的表格展示差异及真实咨询入口。资料不足时先提问，不虚构价格、折扣、免费试用或退款政策，不生成无法支付的结算流程。',
      },
      {
        label: '预约演示',
        prompt:
          '制作一个产品演示或咨询预约介绍页，说明适合谁、可以了解什么、预约流程和需要准备的信息。先确认真实预约链接或公开联系方式，使用已有预约系统入口，不生成无法提交的表单，不展示虚构可用时段或预约成功状态。',
      },
      {
        label: '合作伙伴',
        prompt:
          '制作一个合作伙伴介绍页，根据可公开的合作资料，按合作类型展示伙伴、合作内容、相关案例和真实链接。先确认允许展示的名称与Logo，不编造合作关系、伙伴级别或联合背书；资料不足时先问我。',
      },
      {
        label: '渠道招募',
        prompt:
          '制作一个渠道或代理合作招募页，介绍合作对象、产品支持、加入条件、合作流程及联系入口。先核实区域、授权范围、费用和合作政策；不编造返佣比例、独家授权、收益保证或招商名额，申请使用真实外部入口。',
      },
      {
        label: '加入我们',
        prompt:
          '制作一个招聘介绍页，包含团队介绍、真实在招岗位、职责要求、工作地点、招聘流程和投递方式。先读取已有招聘资料，缺少岗位、薪资或投递入口时向我确认；不编造职位、福利或远程办公政策，不生成无法提交的简历表单。',
      },
      {
        label: '联系我们',
        prompt:
          '制作一个企业联系页，按销售咨询、商务合作和售后等实际需求整理联系入口，并展示已确认的办公地址、服务时间和到访说明。参考站点公开资料；不编造电话、邮箱、地址或地图坐标，不生成不可用的留言表单。',
      },
      {
        label: '门店与网点',
        prompt:
          '制作一个门店或服务网点介绍页，按城市展示真实名称、地址、营业时间、服务范围、电话和地图跳转链接。先确认网点清单及资料时效，可做本页静态城市筛选；不编造网点、距离或实时营业状态，不声称支持当前位置定位。',
      },
      {
        label: '资料下载',
        prompt:
          '制作一个企业资料下载页，按产品手册、白皮书、安装指南等实际资料分类，搜索站内附件并展示名称、用途、可确认的版本和真实下载链接。没有对应附件时先问我，不编造文件、版本、文件大小或下载次数，不添加虚假的留资解锁流程。',
      },
      {
        label: '客户支持',
        prompt:
          '制作一个客户支持导航页，整合真实的使用指南、常见问题、售后说明、工单系统和联系入口，按客户遇到的问题组织路径。读取已有公开说明；响应时间、保修和退换政策缺少依据时先问我，不伪造在线客服或工单提交功能。',
      },
    ],
  },
  {
    label: '个人、团队与内容',
    presets: [
      {
        label: '关于我',
        prompt:
          '制作一个关于我的独立页面。先参考站点介绍、已有关于页和相关公开文章，整理个人简介、关注领域、代表内容及真实联系方式；查找可用头像。沿用当前主题风格，不重复页头页脚，不编造经历。缺少必要信息时先向我提问。',
      },
      {
        label: '项目与作品',
        prompt:
          '制作一个项目与作品展示页。先查找站内相关内容和附件，按项目展示简介、我的工作、真实截图和访问链接。沿用当前主题风格，不编造项目成果；如果无法判断要展示哪些项目，先让我选择。',
      },
      {
        label: '专题导读',
        prompt:
          '制作一个文章专题导读页。先让我明确专题，再检索并阅读相关公开文章，按入门到进阶组织阅读路线，为每篇文章写简短导读并保留真实链接。说明收录内容是本次整理的结果，不承诺自动更新。',
      },
      {
        label: '活动介绍',
        prompt:
          '制作一个活动介绍页，包含活动亮点、时间地点、日程、参与方式和常见问题。优先使用站内已有活动资料与图片；时间、费用和报名链接缺失时先问我，不编造名额或嘉宾。使用真实报名链接，不生成无法提交的表单。',
      },
      {
        label: '资源导航',
        prompt:
          '制作一个资源导航页。先明确我想整理的资源类型，再参考站点菜单、公开文章和附件，按用途分组展示资源名称、简介和真实链接。下载资料使用实际附件地址，不虚构资源，不声称已验证未检查的外部链接。',
      },
      {
        label: '常见问题',
        prompt:
          '制作一个常见问题页面。先确认问题主题，再阅读相关公开文章或说明页面，按类别整理简洁答案，保留必要的来源与联系入口。可以使用折叠交互；没有资料支持的服务承诺、价格或政策先向我确认。',
      },
    ],
  },
]

function openEditor(name: string) {
  void router.push({ name: 'AiPageBuilderEditor', params: { name } })
}
function cloned(name: string) {
  cloneTarget.value = undefined
  openEditor(name)
}
// Published pages go straight to the public URL; drafts fall back to the theme preview.
function open(item: PageSummary) {
  const url = item.publishedRevision ? `/${item.slug}` : pagePreviewUrl(item.name)
  window.open(url, '_blank', 'noopener')
}
function openCreate() {
  form.value = { title: '', slug: '', model: '', prompt: '', themeLayout: true }
  createOpen.value = true
}
const createMutation = useMutation({
  mutationFn: async (value: { title: string; slug: string; themeLayout: boolean }) =>
    (await aiPageBuilderApi.createPage({ createPageRequest: value })).data,
  onSuccess: (detail) => {
    void queryClient.invalidateQueries({ queryKey: pageKeys.all })
    createOpen.value = false
    const prompt = String(form.value.prompt || '').trim()
    void router.push({
      name: 'AiPageBuilderEditor',
      params: { name: detail.page.name },
      // history.state handoff: the editor starts the assistant once, without URL noise.
      ...(prompt ? { state: { aiPrompt: prompt, aiModel: form.value.model } } : {}),
    })
  },
  onError: (e) => Toast.error(errorMessage(e)),
  queryClient,
})
function create() {
  if (!createMutation.isPending.value)
    createMutation.mutate({
      title: form.value.title,
      slug: form.value.slug,
      themeLayout: form.value.themeLayout,
    })
}
const exportMutation = useMutation({
  mutationFn: async (item: PageSummary) => {
    const data = (await aiPageBuilderApi.exportPage({ name: item.name }, { responseType: 'blob' }))
      .data
    return { data, slug: item.slug }
  },
  onSuccess: ({ data, slug }) => {
    const url = URL.createObjectURL(new Blob([data], { type: 'application/zip' }))
    const link = document.createElement('a')
    link.href = url
    link.download = `${slug}.zip`
    document.body.append(link)
    link.click()
    link.remove()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
    Toast.success('已导出保存的草稿')
  },
  onError: (e) => Toast.error(errorMessage(e)),
  queryClient,
})
const removeMutation = useMutation({
  mutationFn: async (item: PageSummary) => {
    await aiPageBuilderApi.deletePage({ name: item.name, version: item.version })
  },
  onSuccess: () => {
    void queryClient.invalidateQueries({ queryKey: pageKeys.all })
    Toast.success('页面已删除')
  },
  onError: (e) => Toast.error(errorMessage(e)),
  queryClient,
})
const publishMutation = useMutation({
  mutationFn: async ({ item, unpublish }: { item: PageSummary; unpublish: boolean }) => {
    const request = { name: item.name, mutationRequest: { version: item.version } }
    await (unpublish
      ? aiPageBuilderApi.unpublishPage(request)
      : aiPageBuilderApi.publishPage(request))
    return unpublish
  },
  onSuccess: (unpublish) => {
    void queryClient.invalidateQueries({ queryKey: pageKeys.all })
    Toast.success(unpublish ? '已取消发布' : '已发布当前草稿')
  },
  onError: (e) => Toast.error(errorMessage(e)),
  queryClient,
})
const mutating = computed(
  () =>
    createMutation.isPending.value ||
    exportMutation.isPending.value ||
    removeMutation.isPending.value ||
    publishMutation.isPending.value,
)
function remove(item: PageSummary) {
  Dialog.warning({
    title: `删除「${item.title}」？`,
    description: '页面会立即下线，所有历史版本和对话也会删除，此操作无法撤销。',
    confirmType: 'danger',
    confirmText: '删除',
    cancelText: '取消',
    onConfirm: () => removeMutation.mutate(item),
  })
}
function confirmPublish(item: PageSummary, unpublish = false) {
  Dialog.warning({
    title: unpublish ? '取消发布此页面？' : '发布当前已保存的草稿？',
    description: unpublish ? '访客将无法访问此页面。' : '访客将看到当前草稿的内容。',
    confirmText: '确认',
    cancelText: '取消',
    onConfirm: () => publishMutation.mutate({ item, unpublish }),
  })
}
</script>

<template>
  <VPageHeader title="AI 页面构建">
    <template #icon><IconGrid /></template>
    <template #actions>
      <VButton type="secondary" @click="openCreate">
        <template #icon><IconAddCircle /></template>
        新建页面
      </VButton>
    </template>
  </VPageHeader>
  <div class="m-2 md:m-4">
    <div class="mb-6">
      <SearchInput v-model="keyword" placeholder="搜索标题或路径" />
    </div>
    <VLoading v-if="listQuery.isLoading.value" />
    <VAlert v-else-if="listQuery.isError.value" type="error" title="页面列表加载失败">
      <template #actions><VButton @click="listQuery.refetch()">重试</VButton> </template>
    </VAlert>
    <VEmpty
      v-else-if="!items.length"
      :title="keyword ? '没有找到匹配的页面' : '还没有主题页面'"
      :message="keyword ? '试试其它标题或路径。' : '点击右上角新建页面，开始设计你的第一个页面。'"
    />
    <div
      v-else
      class="grid grid-cols-[minmax(0,1fr)] gap-5 sm:grid-cols-[repeat(auto-fill,minmax(280px,1fr))]"
    >
      <article
        v-for="item in items"
        :key="item.name"
        class="min-w-0 overflow-hidden rounded-lg border border-gray-200 bg-white transition-shadow hover:border-gray-300 hover:shadow-md"
      >
        <PagePreviewCard
          :name="item.name"
          :revision="item.draftRevision || ''"
          :title="item.title"
          @open="openEditor(item.name)"
        />
        <div class="p-4">
          <RouterLink
            :to="{ name: 'AiPageBuilderEditor', params: { name: item.name } }"
            class="block truncate text-sm font-semibold text-gray-800 hover:underline"
            >{{ item.title }}</RouterLink
          >
          <p class="mt-1 break-all text-xs text-gray-400">/{{ item.slug }}</p>
          <div class="mt-3.5 flex items-center justify-between gap-2">
            <VStatusDot
              :state="
                item.publishedRevision
                  ? item.hasUnpublishedChanges
                    ? 'warning'
                    : 'success'
                  : 'default'
              "
              :text="
                item.publishedRevision
                  ? item.hasUnpublishedChanges
                    ? '有未发布修改'
                    : '已发布'
                  : '未发布'
              "
            />
            <span class="text-[11px] text-gray-400">
              {{ item.updatedAt ? new Date(item.updatedAt).toLocaleDateString() : '' }}
            </span>
          </div>
          <div class="mt-4 flex items-center gap-2 border-t border-gray-100 pt-3.5">
            <VButton size="sm" class="flex-1" @click="open(item)">
              {{ item.publishedRevision ? '访问页面 ↗' : '主题预览 ↗' }}
            </VButton>
            <VDropdown>
              <VButton size="sm" :disabled="mutating" :aria-label="`${item.title}的操作`">
                <IconMore />
              </VButton>
              <template #popper>
                <VDropdownItem v-close-popper :disabled="mutating" @click="cloneTarget = item">
                  克隆页面
                </VDropdownItem>
                <VDropdownItem
                  v-close-popper
                  :disabled="mutating"
                  @click="exportMutation.mutate(item)"
                  >导出草稿 ZIP</VDropdownItem
                >
                <VDropdownItem v-close-popper :disabled="mutating" @click="confirmPublish(item)">
                  {{ item.publishedRevision ? '更新发布' : '发布' }}
                </VDropdownItem>
                <VDropdownItem
                  v-if="item.publishedRevision"
                  v-close-popper
                  :disabled="mutating"
                  @click="confirmPublish(item, true)"
                >
                  取消发布
                </VDropdownItem>
                <VDropdownItem
                  v-close-popper
                  type="danger"
                  :disabled="mutating"
                  @click="remove(item)"
                >
                  删除页面
                </VDropdownItem>
              </template>
            </VDropdown>
          </div>
        </div>
      </article>
    </div>
    <div v-if="total" class="mt-6">
      <VPagination
        :page="page"
        :size="size"
        :total="total"
        class="bg-transparent"
        :size-options="[10, 20, 50]"
        @update:page="page = $event"
        @update:size="((size = $event), (page = 1))"
      />
    </div>
  </div>
  <ClonePageModal
    v-if="cloneTarget"
    :page="cloneTarget"
    @close="cloneTarget = undefined"
    @created="cloned"
  />
  <VModal
    v-if="createOpen"
    title="新建页面"
    :width="500"
    @close="!createMutation.isPending.value && (createOpen = false)"
  >
    <FormKit
      v-model="form"
      type="form"
      :actions="false"
      :disabled="createMutation.isPending.value"
      @node="formNode = $event"
      @submit="create"
    >
      <FormKit
        type="text"
        name="title"
        label="页面标题"
        placeholder="例如：关于我们"
        validation="required|length:1,200"
      />
      <FormKit
        type="text"
        name="slug"
        label="访问路径"
        placeholder="company/about"
        help="使用小写字母、数字和连字符，支持 / 分隔的多级路径，不含首尾斜线。"
        :validation="slugValidation"
        :maxlength="200"
      />
      <FormKit
        type="checkbox"
        name="themeLayout"
        label="复用主题布局"
        help="关闭后页面独立渲染，不套用主题的页头页尾与基础样式。"
      />
      <FormKit
        type="aiModelSelector"
        name="model"
        label="模型（可选）"
        model-type="language"
        required-features="tool-call"
        placeholder="选择模型"
      />
      <FormKit
        type="textarea"
        name="prompt"
        label="初始提示词（可选）"
        placeholder="描述想要的页面，创建后 AI 将立即开始生成"
        auto-height
        maxlength="8000"
      />
      <div class="py-4 max-h-52 space-y-3 overflow-y-auto pr-1">
        <div v-for="group in promptPresetGroups" :key="group.label">
          <p class="mb-1.5 text-xs text-gray-400">{{ group.label }}</p>
          <div class="flex flex-wrap gap-1.5">
            <button
              v-for="preset in group.presets"
              :key="preset.label"
              type="button"
              class="rounded-full border border-gray-200 px-2.5 py-1 text-xs text-gray-600 transition-colors hover:border-gray-300 hover:bg-gray-50 hover:text-gray-900"
              @click="form.prompt = preset.prompt"
            >
              {{ preset.label }}
            </button>
          </div>
        </div>
      </div>
    </FormKit>
    <template #footer>
      <VSpace>
        <VButton
          :loading="createMutation.isPending.value"
          :disabled="createMutation.isPending.value"
          @click="formNode?.submit()"
          type="secondary"
        >
          创建页面
        </VButton>
        <VButton :disabled="createMutation.isPending.value" @click="createOpen = false">
          取消
        </VButton>
      </VSpace>
    </template>
  </VModal>
</template>
