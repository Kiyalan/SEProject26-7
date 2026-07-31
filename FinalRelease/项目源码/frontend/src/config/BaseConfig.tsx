/** User-facing product name shown in header, login, chat, etc. */
export const projectDisplayName = 'SEProject26-7'

/** Lowercase slug for branch prefixes and other machine-readable identifiers. */
export const projectDisplayNameLower = projectDisplayName.toLowerCase()

/**
 * camelCase prefix for localStorage keys.
 * Combine with a PascalCase suffix: `${projectDisplayNameCamel}GithubToken`
 */
export const projectDisplayNameCamel = projectDisplayNameLower.replace(/[^a-z0-9]/g, '')
