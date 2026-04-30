import type { ErrorType } from '../components/ErrorMessage';

export function inferErrorType(message: string): ErrorType {
    const lower = message.toLowerCase();
    if (lower.includes('rate limit') || lower.includes('429') || lower.includes('too many')) {
        return 'rate_limit';
    }
    if (lower.includes('context') && (lower.includes('length') || lower.includes('long') || lower.includes('limit'))) {
        return 'context_length';
    }
    if (lower.includes('network') || lower.includes('timeout') || lower.includes('connection')) {
        return 'network';
    }
    return 'unknown';
}
