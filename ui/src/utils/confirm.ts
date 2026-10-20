import { Dialog } from '@halo-dev/components'

export function confirmDialog(title: string, description: string): Promise<boolean> {
  return new Promise((resolve) =>
    Dialog.warning({
      title,
      description,
      confirmText: '继续',
      cancelText: '取消',
      onConfirm: () => resolve(true),
      onCancel: () => resolve(false),
    }),
  )
}
