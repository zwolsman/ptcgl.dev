import { useLocation } from "react-router"
import { LOCALES } from "~/lib/locale"

export function LocaleSwitcher({ locale }: { locale: string }) {
  const { pathname } = useLocation()

  return (
    <form method="post" action="/_locale">
      <input type="hidden" name="redirectTo" value={pathname} />
      <select
        name="locale"
        defaultValue={locale}
        aria-label="Language"
        className="text-xs bg-transparent text-muted-foreground hover:text-foreground cursor-pointer outline-none border-0 py-1"
        onChange={(e) => e.currentTarget.form?.submit()}
      >
        {LOCALES.map((l) => (
          <option key={l.code} value={l.code}>
            {l.label}
          </option>
        ))}
      </select>
    </form>
  )
}
