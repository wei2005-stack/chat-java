<script setup lang="ts">
import { computed, h, onMounted, ref, watch } from 'vue'
import { NButton, NDataTable, NEmpty, NInput, NTag, useDialog, useMessage } from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { createKnowledgeDocument, deleteKnowledgeDocument, listKnowledgeDocuments, searchKnowledge } from '@/api/knowledge'
import type { KnowledgeDocument, KnowledgeSearchHit } from '@/api/knowledge'
import { SvgIcon } from '@/components/common'
import { useAuthStore } from '@/store'
import Permission from '@/views/components/Permission.vue'

const authStore = useAuthStore()
const dialog = useDialog()
const message = useMessage()

const needPermission = computed(() => authStore.isInitialized && !authStore.isInitializing && !authStore.isValid)

const title = ref('')
const content = ref('')
const fileInput = ref<HTMLInputElement | null>(null)

const documents = ref<KnowledgeDocument[]>([])
const loading = ref(false)
const submitting = ref(false)

const query = ref('')
const hits = ref<KnowledgeSearchHit[]>([])
const searching = ref(false)
const searched = ref(false)

const canSubmit = computed(() => !!title.value.trim() && !!content.value.trim())

function errorMessage(error: unknown, fallback: string): string {
  const data = error as { message?: string } | undefined
  return data?.message || fallback
}

function formatTime(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime()))
    return value
  return date.toLocaleString('zh-CN', { hour12: false })
}

function excerpt(value: string): string {
  const text = value.trim()
  return text.length > 240 ? `${text.slice(0, 240)}…` : text
}

async function loadDocuments() {
  loading.value = true
  try {
    documents.value = await listKnowledgeDocuments()
  }
  catch (error) {
    message.error(errorMessage(error, '加载文档列表失败'))
  }
  finally {
    loading.value = false
  }
}

function pickFile() {
  fileInput.value?.click()
}

function handleFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file)
    return

  if (!/\.(txt|md|markdown)$/i.test(file.name)) {
    message.error('请选择 .txt 或 .md 文件')
    return
  }

  const reader = new FileReader()
  reader.onload = () => {
    content.value = String(reader.result ?? '')
    if (!title.value.trim())
      title.value = file.name.replace(/\.(txt|md|markdown)$/i, '')
    message.success(`已读取文件：${file.name}`)
  }
  reader.onerror = () => message.error('读取文件失败')
  reader.readAsText(file)
}

function resetForm() {
  title.value = ''
  content.value = ''
}

async function handleCreate() {
  if (!canSubmit.value)
    return

  submitting.value = true
  try {
    const document = await createKnowledgeDocument(title.value.trim(), content.value)
    documents.value = [document, ...documents.value.filter(item => item.uuid !== document.uuid)]
    resetForm()
    message.success('文档上传成功')
  }
  catch (error) {
    message.error(errorMessage(error, '文档上传失败'))
  }
  finally {
    submitting.value = false
  }
}

function handleDelete(document: KnowledgeDocument) {
  dialog.warning({
    title: '删除文档',
    content: `确定删除《${document.title}》吗？删除后不可恢复。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await deleteKnowledgeDocument(document.uuid)
        documents.value = documents.value.filter(item => item.uuid !== document.uuid)
        message.success('文档已删除')
      }
      catch (error) {
        message.error(errorMessage(error, '文档删除失败'))
        await loadDocuments()
      }
    },
  })
}

async function handleSearch() {
  const keyword = query.value.trim()
  if (!keyword)
    return

  searching.value = true
  try {
    hits.value = await searchKnowledge(keyword)
    searched.value = true
    if (hits.value.length === 0)
      message.warning('没有找到相关片段')
  }
  catch (error) {
    message.error(errorMessage(error, '检索失败'))
  }
  finally {
    searching.value = false
  }
}

const rowKey = (row: KnowledgeDocument) => row.uuid

const columns: DataTableColumns<KnowledgeDocument> = [
  {
    title: '标题',
    key: 'title',
    ellipsis: { tooltip: true },
  },
  {
    title: '分块数',
    key: 'chunkCount',
    width: 110,
    render: row => h(NTag, { size: 'small', type: 'info', bordered: false }, { default: () => `${row.chunkCount} 段` }),
  },
  {
    title: '创建时间',
    key: 'createdAt',
    width: 190,
    render: row => formatTime(row.createdAt),
  },
  {
    title: '操作',
    key: 'actions',
    width: 90,
    align: 'right',
    render: row => h(NButton, {
      size: 'small',
      quaternary: true,
      type: 'error',
      onClick: () => handleDelete(row),
    }, { default: () => '删除' }),
  },
]

onMounted(async () => {
  await authStore.initializeAuth()
  if (authStore.isValid)
    await loadDocuments()
})

watch(() => authStore.isValid, (isValid) => {
  if (isValid && !loading.value)
    loadDocuments()
})
</script>

<template>
  <div class="flex flex-col w-full h-full dark:text-white">
    <header
      class="flex items-center justify-between h-16 z-30 border-b dark:border-neutral-800 bg-white/80 dark:bg-black/20 backdrop-blur"
    >
      <div class="flex items-center ml-1 md:ml-10 gap-2">
        <SvgIcon icon="ri:book-line" class="w-6 h-6" />
        <h1 class="text-xl font-semibold text-gray-900 dark:text-gray-100">
          知识库
        </h1>
      </div>
      <div class="mr-1 md:mr-10 flex items-center gap-3">
        <span class="text-sm text-gray-500 dark:text-gray-400">共 {{ documents.length }} 个文档</span>
        <NButton size="small" quaternary :loading="loading" @click="loadDocuments">
          <template #icon>
            <SvgIcon icon="ri:refresh-line" />
          </template>
          刷新
        </NButton>
      </div>
    </header>

    <div class="flex-1 min-h-0 overflow-y-auto bg-gray-50 dark:bg-[#24272e]">
      <div class="max-w-screen-xl px-4 py-6 mx-auto space-y-6">
        <section class="rounded-xl border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-800 shadow-sm p-5">
          <div class="flex items-start justify-between gap-4 mb-4">
            <div>
              <h2 class="text-base font-semibold text-gray-900 dark:text-gray-100">
                上传文档
              </h2>
              <p class="mt-1 text-sm text-gray-500 dark:text-gray-400">
                仅支持纯文本，上传后会自动切块并用于问答检索。
              </p>
            </div>
            <div class="flex-none">
              <input ref="fileInput" type="file" accept=".txt,.md,.markdown" class="hidden" @change="handleFileChange">
              <NButton size="small" secondary @click="pickFile">
                <template #icon>
                  <SvgIcon icon="ri:file-text-line" />
                </template>
                选择 .txt / .md
              </NButton>
            </div>
          </div>

          <div class="space-y-3">
            <NInput
              v-model:value="title"
              clearable
              show-count
              :maxlength="100"
              placeholder="文档标题，例如：云杉科技员工手册"
            />
            <NInput
              v-model:value="content"
              type="textarea"
              placeholder="粘贴或输入文档正文..."
              :autosize="{ minRows: 6, maxRows: 14 }"
            />
            <div class="flex items-center justify-between">
              <span class="text-xs text-gray-400 dark:text-gray-500">{{ content.length }} 字</span>
              <div class="flex items-center gap-2">
                <NButton quaternary :disabled="submitting" @click="resetForm">
                  清空
                </NButton>
                <NButton type="primary" :loading="submitting" :disabled="!canSubmit" @click="handleCreate">
                  上传文档
                </NButton>
              </div>
            </div>
          </div>
        </section>

        <section class="rounded-xl border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-800 shadow-sm p-5">
          <div class="mb-4">
            <h2 class="text-base font-semibold text-gray-900 dark:text-gray-100">
              文档列表
            </h2>
            <p class="mt-1 text-sm text-gray-500 dark:text-gray-400">
              已上传的文档会按段落切块存储。
            </p>
          </div>

          <NDataTable
            :columns="columns"
            :data="documents"
            :loading="loading"
            :row-key="rowKey"
            :bordered="false"
            size="small"
          >
            <template #empty>
              <NEmpty description="还没有上传任何文档">
                <template #extra>
                  <span class="text-xs text-gray-400 dark:text-gray-500">在上方填写标题和正文，或直接选择 .txt / .md 文件</span>
                </template>
              </NEmpty>
            </template>
          </NDataTable>
        </section>

        <section class="rounded-xl border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-800 shadow-sm p-5">
          <div class="mb-4">
            <h2 class="text-base font-semibold text-gray-900 dark:text-gray-100">
              检索测试
            </h2>
            <p class="mt-1 text-sm text-gray-500 dark:text-gray-400">
              模拟提问时的片段检索，用于演示 RAG 命中效果。
            </p>
          </div>

          <div class="flex items-center gap-2">
            <NInput
              v-model:value="query"
              clearable
              placeholder="输入问题，例如：年假有几天"
              @keyup.enter="handleSearch"
            />
            <NButton type="primary" :loading="searching" :disabled="!query.trim()" @click="handleSearch">
              检索
            </NButton>
          </div>

          <p v-if="!searched" class="mt-4 text-sm text-gray-400 dark:text-gray-500">
            输入问题后点击“检索”，查看命中的知识库片段。
          </p>

          <div v-else class="mt-4 space-y-3">
            <NEmpty v-if="hits.length === 0" description="没有找到相关片段，换个问法试试" />
            <div
              v-for="(hit, index) in hits" :key="`${hit.documentUuid}-${hit.chunkIndex}`"
              class="rounded-lg border border-gray-200 dark:border-gray-700 bg-gray-50 dark:bg-gray-900/40 p-4"
            >
              <div class="flex items-center justify-between gap-3 mb-2">
                <div class="flex items-center gap-2 min-w-0">
                  <span class="flex-none px-1.5 py-0.5 text-xs rounded bg-blue-50 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400">
                    #{{ index + 1 }}
                  </span>
                  <span class="text-sm font-medium text-gray-900 dark:text-gray-100 truncate">
                    {{ hit.documentTitle }}
                  </span>
                  <span class="flex-none text-xs text-gray-500 dark:text-gray-400">第 {{ hit.chunkIndex + 1 }} 段</span>
                </div>
                <span class="flex-none text-xs text-gray-500 dark:text-gray-400">得分 {{ hit.score }}</span>
              </div>
              <p class="text-sm leading-6 text-gray-600 dark:text-gray-300 whitespace-pre-wrap break-words">
                {{ excerpt(hit.content) }}
              </p>
            </div>
          </div>
        </section>
      </div>
    </div>

    <Permission :visible="needPermission" />
  </div>
</template>
