import type { Env } from '../index';

export interface SendEmailOptions {
  to: string;
  subject: string;
  text: string;
  html?: string;
}

export interface EmailProvider {
  name: string;
  sendEmail(options: SendEmailOptions): Promise<boolean>;
  sendVerificationCode(to: string, code: string): Promise<boolean>;
}

/**
 * Resend.com provider (standard server-side transactional email API).
 */
export class ResendEmailProvider implements EmailProvider {
  name = 'resend';

  constructor(private apiKey: string, private from: string = 'BAZICHE <noreply@baziche.app>') {}

  async sendEmail(options: SendEmailOptions): Promise<boolean> {
    try {
      const resp = await fetch('https://api.resend.com/emails', {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${this.apiKey}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          from: this.from,
          to: [options.to],
          subject: options.subject,
          text: options.text,
          html: options.html,
        }),
      });
      return resp.ok;
    } catch {
      return false;
    }
  }

  async sendVerificationCode(to: string, code: string): Promise<boolean> {
    return this.sendEmail({
      to,
      subject: 'کد تأیید بازیچه (BAZICHE)',
      text: `سلام،\n\nکد تأیید حساب شما در بازیچه: ${code}\n\nاین کد تا ۱۰ دقیقه معتبر است.\nتیم بازیچه`,
      html: `
        <div dir="rtl" style="font-family: sans-serif; background: #121816; color: #f3f4f6; padding: 24px; border-radius: 12px; max-width: 480px; margin: auto;">
          <h2 style="color: #10b981; margin-top: 0;">بازیچه | ساخت بازی بدون کدنویسی</h2>
          <p>سلام، کد تأیید ورود و فعال‌سازی حساب شما:</p>
          <div style="font-size: 32px; font-weight: bold; letter-spacing: 6px; color: #b8e62e; background: #1a2520; padding: 16px; text-align: center; border-radius: 8px; margin: 20px 0;">
            ${code}
          </div>
          <p style="color: #9ca3af; font-size: 13px;">این کد تا ۱۰ دقیقه دیگر منقضی می‌شود. اگر شما این درخواست را نداده‌اید، این ایمیل را نادیده بگیرید.</p>
        </div>
      `,
    });
  }
}

/**
 * Gmail / SMTP Compatible Provider (HTTP Gateway or Worker SMTP endpoint).
 */
export class GmailCompatibleProvider implements EmailProvider {
  name = 'gmail-compatible';

  constructor(private user: string, private appPassword?: string, private gatewayUrl?: string) {}

  async sendEmail(options: SendEmailOptions): Promise<boolean> {
    if (this.gatewayUrl) {
      try {
        const resp = await fetch(this.gatewayUrl, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            auth: { user: this.user, pass: this.appPassword },
            message: options,
          }),
        });
        return resp.ok;
      } catch {
        return false;
      }
    }
    // Direct log fallback
    console.info(`[GmailCompatibleProvider] Email to ${options.to}: ${options.text}`);
    return true;
  }

  async sendVerificationCode(to: string, code: string): Promise<boolean> {
    return this.sendEmail({
      to,
      subject: 'کد تأیید بازیچه (BAZICHE)',
      text: `کد تأیید شما در بازیچه: ${code} (اعتبار: ۱۰ دقیقه)`,
    });
  }
}

/**
 * Development & Test Fallback Provider:
 * Logs code safely to console/telemetry and always succeeds without requiring external credentials.
 */
export class DevEmailProvider implements EmailProvider {
  name = 'dev-console';

  async sendEmail(options: SendEmailOptions): Promise<boolean> {
    console.info(`[DevEmailProvider] [MOCK SEND] To: ${options.to} | Subject: ${options.subject} | Text: ${options.text}`);
    return true;
  }

  async sendVerificationCode(to: string, code: string): Promise<boolean> {
    console.info(`[DevEmailProvider] Verification code for ${to}: ${code}`);
    return true;
  }
}

/**
 * Factory for the active EmailProvider based on Worker environment.
 */
export function getEmailProvider(env: Env): EmailProvider {
  if (env.RESEND_API_KEY) {
    return new ResendEmailProvider(env.RESEND_API_KEY, env.EMAIL_FROM);
  }
  if (env.GMAIL_USER) {
    return new GmailCompatibleProvider(env.GMAIL_USER, env.GMAIL_APP_PASSWORD);
  }
  return new DevEmailProvider();
}
