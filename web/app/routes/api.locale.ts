import { redirect } from "react-router"
import { isValidLocale, setLocaleCookie } from "~/lib/locale"
import type { ActionFunctionArgs } from "react-router"

export async function action({ request }: ActionFunctionArgs) {
  const formData = await request.formData()
  const locale = formData.get("locale") as string
  const redirectTo = (formData.get("redirectTo") as string) || "/"

  return redirect(redirectTo, {
    headers: isValidLocale(locale) ? { "Set-Cookie": setLocaleCookie(locale) } : {},
  })
}
