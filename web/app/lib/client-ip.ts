export function getClientIp(request: Request): string | null {
  const xff = request.headers.get("x-forwarded-for")
  if (!xff) return null
  return xff.split(",")[0].trim() || null
}
