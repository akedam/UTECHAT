export function normalizePhoneNumber(input) {
  const value = String(input || '').trim().replace(/[ .()-]/g, '')
  const number = /^0[35789]\d{8}$/.test(value) ? '+84' + value.slice(1) : value
  return /^\+[1-9]\d{7,14}$/.test(number) ? number : null
}
