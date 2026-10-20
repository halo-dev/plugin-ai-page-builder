import { onMounted, shallowRef } from 'vue'
import { getPublicSiteUrl } from '../api/clients'

export function usePublicSiteUrl() {
  const siteBaseUrl = shallowRef('')
  // Resolve the site URL up front so opening a page stays inside the click's user gesture.
  onMounted(() => {
    void getPublicSiteUrl()
      .then((url) => {
        siteBaseUrl.value = url
      })
      .catch(() => {
        // Keep the Console origin as a best-effort fallback.
      })
  })
  function pageUrl(slug: string) {
    const base = (siteBaseUrl.value || window.location.origin).replace(/\/$/, '')
    return `${base}/${slug}`
  }
  return { pageUrl }
}
