/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import { useState } from 'react';
import {
  CheckCircle2,
  Copy,
  Download,
  FolderGit2,
  PackageCheck,
  Rocket,
  ShieldCheck,
  Smartphone,
} from 'lucide-react';

const STAGES = [
  {
    title: 'المرحلة 1: اكتشاف أجهزة الإقران تلقائياً',
    detail: 'عرض الأجهزة القريبة (_adb-tls-pairing._tcp) وتعبئة الـ IP والبورت بضغطة واحدة.',
  },
  {
    title: 'المرحلة 2: الإضافة التلقائية بعد الإقران',
    detail: 'اكتشاف بورت الاتصال وعرض رسالة تأكيد باسم الجهاز قبل الحفظ والاتصال.',
  },
  {
    title: 'المرحلة 3: الإقران عبر رمز QR',
    detail: 'توليد رمز QR عشوائي مؤقت (WIFI:T:ADB) يمسحه الهاتف الآخر من خيارات المطور.',
  },
  {
    title: 'المرحلة 4: تسريع بدء الاتصال',
    detail: 'التحقق السريع من بصمة السيرفر عبر sha256sum لتجنب إعادة النقل دون تخطي SHA-256.',
  },
  {
    title: 'المرحلة 5: تجربة الضغطة الواحدة',
    detail: 'اتصال سريع (Quick connect) + ضغطة واحدة للأجهزة المحفوظة + خيار إعادة الاتصال التلقائي.',
  },
  {
    title: 'المرحلة 6: دليل البداية وأخطاء واضحة',
    detail: 'شاشة شرح من 3 خطوات عند أول تشغيل + رسائل خطأ مبسطة بالعربية والإنجليزية مع زر التفاصيل.',
  },
];

const GIT_COMMANDS = `unzip scrcpy-client-repo.zip -d scrcpy-client-v2
cd scrcpy-client-v2
git init -b main
git add .
git commit -m "Initial commit: Stages 1-6 + APK release workflow"
git remote add origin https://github.com/USERNAME/NEW-REPO-NAME.git
git push -u origin main`;

export default function App() {
  const [copied, setCopied] = useState(false);

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(GIT_COMMANDS);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      setCopied(false);
    }
  };

  return (
    <div dir="rtl" className="min-h-screen bg-slate-950 text-slate-100 antialiased">
      <div className="mx-auto max-w-4xl px-5 py-10">
        <header className="mb-8 rounded-2xl border border-slate-800 bg-slate-900/80 p-6 sm:p-8">
          <div className="mb-3 inline-flex items-center gap-2 rounded-lg bg-emerald-500/10 px-3 py-1 text-sm font-medium text-emerald-300">
            <FolderGit2 className="h-4 w-4" />
            <span>مشروع Android منظم وجاهز للرفع على GitHub</span>
          </div>
          <h1 className="text-2xl font-bold tracking-tight text-white sm:text-3xl">
            حزمة مستودع Scrcpy Client (المراحل 1 – 6)
          </h1>
          <p className="mt-2 text-sm leading-relaxed text-slate-300 sm:text-base">
            تم تنظيم جميع الملفات وتضمين ملف GitHub Actions (<code className="rounded bg-slate-800 px-1.5 py-0.5 text-emerald-300">.github/workflows/release-apk.yml</code>) لبناء ملف APK جاهز ونشره تلقائياً في صفحة <strong>Releases</strong> فور الرفع على <code>main</code>.
          </p>

          <div className="mt-6 flex flex-wrap items-center gap-3">
            <a
              href="/scrcpy-client-repo.zip"
              download="scrcpy-client-repo.zip"
              className="inline-flex items-center gap-2 rounded-xl bg-emerald-500 px-5 py-3 text-sm font-semibold text-slate-950 transition hover:bg-emerald-400"
            >
              <Download className="h-4 w-4" />
              <span>تحميل ملف المشروع الكامل (scrcpy-client-repo.zip)</span>
            </a>
            <span className="text-xs text-slate-400">
              يحتوي على 541 ملفاً (الكود الكامل + الترجمة العربية + اختبارات الوحدة + ملفات GitHub Actions)
            </span>
          </div>
        </header>

        <section className="mb-8 grid gap-4 sm:grid-cols-2">
          <div className="rounded-2xl border border-slate-800 bg-slate-900/60 p-5">
            <div className="mb-2 flex items-center gap-2 text-emerald-300">
              <Rocket className="h-5 w-5" />
              <h2 className="font-semibold text-white">كيف تنشر الـ APK في GitHub Releases؟</h2>
            </div>
            <ol className="space-y-2.5 text-sm leading-relaxed text-slate-300">
              <li>1. أنشئ Repository جديد فارغ على حسابك في GitHub.</li>
              <li>2. حمّل ملف <code className="text-emerald-300">scrcpy-client-repo.zip</code> وفك الضغط عنه.</li>
              <li>3. ارفع الملفات إلى الـ repo الجديد على فرع <code className="text-emerald-300">main</code> (مع التأكد من وجود مجلد <code className="text-emerald-300">.github</code>).</li>
              <li>4. افتح تبويب <strong>Actions</strong> وستجد workflow باسم <strong>Build &amp; Release APK</strong> بدأ تلقائياً.</li>
              <li>5. بعد الانتهاء، افتح تبويب <strong>Releases</strong> لتحميل <code className="text-emerald-300">scrcpy-client-release.apk</code> وتثبيته على الهاتف.</li>
            </ol>
          </div>

          <div className="rounded-2xl border border-slate-800 bg-slate-900/60 p-5">
            <div className="mb-2 flex items-center gap-2 text-sky-300">
              <PackageCheck className="h-5 w-5" />
              <h2 className="font-semibold text-white">مخرجات الـ Workflow التلقائي</h2>
            </div>
            <ul className="space-y-2.5 text-sm leading-relaxed text-slate-300">
              <li className="flex items-start gap-2">
                <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-emerald-400" />
                <span>تشغيل اختبارات الوحدة (<code>:app:testDebugUnitTest</code> و<code>:libadb:testDebugUnitTest</code>) و<code>:app:lintDebug</code>.</span>
              </li>
              <li className="flex items-start gap-2">
                <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-emerald-400" />
                <span>بناء <strong>scrcpy-client-release.apk</strong> (نسخة مضغوطة وموقعة جاهزة للتثبيت المباشر).</span>
              </li>
              <li className="flex items-start gap-2">
                <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-emerald-400" />
                <span>بناء <strong>scrcpy-client-debug.apk</strong> وملف التحقق <strong>SHA256SUMS.txt</strong>.</span>
              </li>
              <li className="flex items-start gap-2">
                <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-emerald-400" />
                <span>رفع الملفات في <strong>Artifacts</strong> ونشرها تلقائياً في <strong>Releases</strong> (تحت وسم <code>latest</code> أو أي tag <code>v*</code>).</span>
              </li>
            </ul>
          </div>
        </section>

        <section className="mb-8 rounded-2xl border border-slate-800 bg-slate-900/60 p-5">
          <div className="mb-3 flex items-center justify-between gap-2">
            <div className="flex items-center gap-2 text-amber-300">
              <Smartphone className="h-5 w-5" />
              <h2 className="font-semibold text-white">أوامر الرفع السريعة (لو بترفع من الكمبيوتر عبر Git)</h2>
            </div>
            <button
              type="button"
              onClick={handleCopy}
              className="inline-flex items-center gap-1.5 rounded-lg border border-slate-700 bg-slate-800 px-3 py-1.5 text-xs font-medium text-slate-200 transition hover:bg-slate-700"
            >
              <Copy className="h-3.5 w-3.5" />
              <span>{copied ? 'تم النسخ' : 'نسخ الأوامر'}</span>
            </button>
          </div>
          <pre dir="ltr" className="overflow-x-auto rounded-xl bg-slate-950 p-4 text-left text-xs leading-relaxed text-emerald-300">
            <code>{GIT_COMMANDS}</code>
          </pre>
        </section>

        <section className="rounded-2xl border border-slate-800 bg-slate-900/60 p-5">
          <div className="mb-4 flex items-center gap-2 text-emerald-300">
            <ShieldCheck className="h-5 w-5" />
            <h2 className="font-semibold text-white">ملخص المراحل المدمجة في المشروع</h2>
          </div>
          <div className="grid gap-3 sm:grid-cols-2">
            {STAGES.map((stage) => (
              <div key={stage.title} className="rounded-xl border border-slate-800/80 bg-slate-950/60 p-4">
                <h3 className="text-sm font-semibold text-white">{stage.title}</h3>
                <p className="mt-1 text-xs leading-relaxed text-slate-400">{stage.detail}</p>
              </div>
            ))}
          </div>
        </section>
      </div>
    </div>
  );
}
