import { axiosInstance } from '@halo-dev/api-client'
import {
  AiPageBuilderApi,
  AiPageBuilderAiV1alpha1ConsoleApi,
  AiPageBuilderChatApi,
  AiPageBuilderChatApiAxiosParamCreator,
  type PageChatRequest,
  type PageDocument,
} from './generated'

export const API_BASE = '/apis/console.api.aipagebuilder.halo.run/v1alpha1'

export const aiPageBuilderApi = new AiPageBuilderApi(undefined, '', axiosInstance)
export const aiPageBuilderAiApi = new AiPageBuilderAiV1alpha1ConsoleApi(
  undefined,
  '',
  axiosInstance,
)
export const aiPageBuilderChatApi = new AiPageBuilderChatApi(undefined, '', axiosInstance)

const chatParameters = AiPageBuilderChatApiAxiosParamCreator()

export type {
  PageChatRun as ChatRun,
  PageChatTurn as ChatTurn,
  PageChatSession as ChatConversation,
} from './generated'

export const chatUrl = (name: string) => `${API_BASE}/pages/${encodeURIComponent(name)}/chat`
export const pagePreviewUrl = (name: string) =>
  `${API_BASE}/pages/${encodeURIComponent(name)}/preview`
export const revisionPreviewUrl = (name: string, revision: string) =>
  `${API_BASE}/pages/${encodeURIComponent(name)}/revisions/${encodeURIComponent(revision)}/preview`

// Render an unsaved, validated candidate with the same template/CSS pipeline as the public page.
export async function renderPagePreview(name: string, document: PageDocument, signal: AbortSignal) {
  return (
    await axiosInstance.post<string>(
      pagePreviewUrl(name),
      { ...document, js: '' },
      {
        signal,
        responseType: 'text',
      },
    )
  ).data
}

export async function prepareChatRequest(name: string, request: PageChatRequest) {
  const args = await chatParameters.runPageChat(name, request)
  return { api: args.url, body: request as unknown as Record<string, unknown> }
}
