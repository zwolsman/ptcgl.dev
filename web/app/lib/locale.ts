export const LOCALES = [
  { code: "en",   label: "English" },
  // { code: "jp",   label: "日本語" },
  { code: "es",   label: "Español" },
  { code: "fr",   label: "Français" },
  { code: "de",   label: "Deutsch" },
  { code: "it",   label: "Italiano" },
  { code: "ptbr", label: "Português (Brasil)" },
] as const;

export type LocaleCode = (typeof LOCALES)[number]["code"];

const VALID_CODES = new Set<string>(LOCALES.map((l) => l.code));

export function isValidLocale(code: string): code is LocaleCode {
  return VALID_CODES.has(code);
}

export function getLocale(request: Request): LocaleCode {
  const cookieHeader = request.headers.get("cookie") ?? "";
  const raw = cookieHeader
    .split(";")
    .map((c) => c.trim().split("="))
    .find(([k]) => k.trim() === "locale")?.[1]
    ?.trim();
  return VALID_CODES.has(raw ?? "") ? (raw as LocaleCode) : "en";
}

export function setLocaleCookie(locale: string): string {
  return `locale=${locale}; Path=/; Max-Age=31536000; SameSite=Lax`;
}
