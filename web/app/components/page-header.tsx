import type { ReactNode } from "react"
import { LocaleSwitcher } from "~/components/locale-switcher"

interface PageHeaderProps {
  locale: string
  children?: ReactNode
}

export function PageHeader({ locale, children }: PageHeaderProps) {
  return (
    <header className="border-b sticky top-0 bg-background/95 backdrop-blur-sm z-10">
      <div className="container mx-auto px-4 h-14 flex items-center gap-3">
        <div className="flex flex-1 items-center gap-3 min-w-0">{children}</div>
        <LocaleSwitcher locale={locale} />
      </div>
    </header>
  )
}
