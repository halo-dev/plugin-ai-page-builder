import { axiosInstance } from '@halo-dev/api-client'
import type { AxiosRequestConfig } from 'axios'
import {
  AiPageBuilderApi,
  AiPageBuilderAiV1alpha1ConsoleApi,
  AiPageBuilderChatApi,
  AiPageBuilderChatApiAxiosParamCreator,
  Configuration,
  type PageChatRequest,
  type PageDocument,
} from './generated'

export const API_BASE = '/apis/console.api.aipagebuilder.halo.run/v1alpha1'

export const aiPageBuilderApi = new AiPageBuilderApi(new Configuration(), '', axiosInstance)
export const aiPageBuilderAiApi = new AiPageBuilderAiV1alpha1ConsoleApi(
  undefined,
  '',
  axiosInstance,
)
export const aiPageBuilderChatApi = new AiPageBuilderChatApi(undefined, '', axiosInstance)

// The Console's global response interceptor toasts ProblemDetail failures unless the
// request config sets `mute`; use this only for speculative calls whose failures are
// already surfaced inline (mid-stream content validation). `mute` also skips the 401
// login-expired dialog for that request, which speculative calls can safely defer to
// the user's next real action — never mute requests whose failure blocks the editor.
export type SilentRequestConfig = AxiosRequestConfig & { mute?: boolean }

const chatParameters = AiPageBuilderChatApiAxiosParamCreator()

export type {
  ImportFinding,
  ImportReview,
  PageChatRun as ChatRun,
  PageChatTurn as ChatTurn,
  PageChatSession as ChatConversation,
} from './generated'

export const chatUrl = (name: string) => `${API_BASE}/pages/${encodeURIComponent(name)}/chat`
export const pagePreviewUrl = (name: string) =>
  `${API_BASE}/pages/${encodeURIComponent(name)}/preview`

// Public links use Halo's configured external URL, which may include a base path.
let publicSiteUrl: Promise<string> | undefined
export function getPublicSiteUrl(): Promise<string> {
  if (!publicSiteUrl) {
    publicSiteUrl = aiPageBuilderApi
      .getSiteUrl()
      .then((response) => response.data.baseUrl)
      .catch((error) => {
        publicSiteUrl = undefined
        throw error
      })
  }
  return publicSiteUrl
}
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
