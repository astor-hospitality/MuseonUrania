"use client";

import { Fragment, useEffect, useRef, useState } from "react";
import Image from "next/image";
import ChatNavigation from "./ChatNavigation";
import { products } from "@/content/products";
import LivePattern from "./LivePattern";
import { vedalina, quickReplies, answerFor } from "@/content/vedalina";
import { site } from "@/content/site";
import { consent as consentCopy } from "@/content/legal";
import {
  apiConfigured,
  callHuman,
  chatPrompts,
  chatStreamUrl,
  chatThread,
  pingTyping,
  raiseChatLead,
  rateAnswer,
  sayInChat,
  visitorKey,
  type ChatLine,
  type ChatLead,
  type NavigationAction,
  type ChatSupport,
  type Handoff,
  type Prompt,
  type Source,
} from "@/lib/submit";
import styles from "./VedalinaChat.module.css";

// Виджет ведёт РАЗГОВОР, а не задаёт разовые вопросы.
//
// Отличие видно не сразу, но оно меняет всё: когда Ведалина не находит ответа
// по опубликованному, разговор не заканчивается тупиком с телефоном — он
// встаёт в очередь к сотруднику, и дальше отвечает человек. Посетителю при
// этом видно, кто именно ответил: выдать ответ поиска за консультацию
// специалиста нельзя ни при каких обстоятельствах.
//
// Лента приходит с сервера целиком и рисуется целиком. Дописывать пришедшее
// к тому, что уже на экране, значит требовать, чтобы клиент и сервер одинаково
// понимали, где кончилось прошлое состояние, — а при обрыве связи они
// понимают это по-разному.

type Message = {
  actions?: NavigationAction[];
  from: "bot" | "me" | "staff";
  /** Имя сотрудника: посетитель должен видеть, что отвечает человек. */
  who?: string;
  text: string;
  /**
   * Идентификатор сообщения на портале. Пусто у приветствия и у реплик,
   * дописанных виджетом при недоступном портале: их на портале нет,
   * и оценить их не к чему.
   */
  id?: string;
  /** Оценка ответа: помог или нет. `null` — не оценивали. */
  helpful?: boolean | null;
  sources?: Source[];
  /** Заполнен, когда подходящих опубликованных источников нет. */
  handoff?: Handoff;
  /** Когда написано. У приветствия пусто: его никто не отправлял. */
  at?: string;
  /**
   * Когда прочитано другой стороной. Показывается только у своих сообщений
   * и значит ровно одно: их увидел живой человек. Ждущему это важнее
   * любой надписи о сроках — она обещание, а отметка факт.
   */
  readAt?: string | null;
};

/** Часы и минуты. Разговор возвращаются читать через час и через день. */
function clock(iso?: string): string | null {
  if (!iso) return null;
  const at = new Date(iso);
  return Number.isNaN(at.valueOf())
    ? null
    : at.toLocaleTimeString("ru-RU", { hour: "2-digit", minute: "2-digit" });
}

const GREETING: Message = { from: "bot", text: vedalina.greeting };

/** Строка серверной ленты — в сообщение виджета. */
function toMessage(line: ChatLine): Message {
  if (line.author === "visitor") {
    return { from: "me", id: line.id, text: line.body, at: line.at, readAt: line.readAt };
  }
  if (line.author === "staff") {
    return {
      from: "staff",
      id: line.id,
      who: line.actor ?? "Специалист VEDAL",
      text: line.body,
      at: line.at,
    };
  }
  return {
    from: "bot",
    id: line.id,
    text: line.body,
    at: line.at,
    helpful: line.helpful,
    actions: line.actions,
    sources: line.sources?.length ? line.sources : undefined,
  };
}

/** Когда виджет последний раз был открыт на глазах у человека. */
const SEEN_KEY = "vedal.chat.seen";

function lastSeen(): number {
  try {
    return Number(localStorage.getItem(SEEN_KEY)) || 0;
  } catch {
    // Приватный режим, запрет сторонних данных: без отметки просто
    // не будет разделителя «новые» — окно от этого не ломается.
    return 0;
  }
}

function rememberSeen(at: number) {
  try {
    localStorage.setItem(SEEN_KEY, String(at));
  } catch {
    // См. выше: невозможность запомнить — не повод рушить виджет.
  }
}

/**
 * Первое сообщение, которого человек ещё не видел.
 *
 * <p>Считается по времени последнего открытия виджета, а не по отметке
 * «прочитано». Отметку портал ставит в тот момент, когда отдаёт ленту:
 * к приходу она уже проставлена, и непрочитанного в ленте не бывает
 * по устройству.
 *
 * <p>Свои сообщения границей не считаются: посетитель их видел, когда писал.
 */
function isFirstUnseen(list: Message[], at: number, seenAt: number): boolean {
  if (!seenAt) return false;

  const message = list[at];
  if (message.from === "me" || !message.at) return false;
  if (new Date(message.at).getTime() <= seenAt) return false;

  // Граница одна — перед первым новым. Иначе надпись повторяется перед
  // каждым сообщением подряд.
  return !list
    .slice(0, at)
    .some((m) => m.from !== "me" && m.at && new Date(m.at).getTime() > seenAt);
}

/** День сообщения: по нему лента делится разделителями дат. */
function day(iso?: string): string | null {
  if (!iso) return null;
  const at = new Date(iso);
  return Number.isNaN(at.valueOf()) ? null : at.toDateString();
}

/**
 * Подпись разделителя даты.
 *
 * «Сегодня» и «вчера» — словами: дата в разговоре нужна, чтобы понять,
 * насколько он свежий, а «31 августа» требует от читателя вспомнить,
 * какое сегодня число.
 */
function dayLabel(iso: string): string {
  const at = new Date(iso);
  const today = new Date();
  const yesterday = new Date(today);
  yesterday.setDate(today.getDate() - 1);

  if (at.toDateString() === today.toDateString()) return "Сегодня";
  if (at.toDateString() === yesterday.toDateString()) return "Вчера";

  return at.toLocaleDateString("ru-RU", {
    day: "numeric",
    month: "long",
    // Год — только у прошлогодних: в свежем разговоре он лишний шум.
    year: at.getFullYear() === today.getFullYear() ? undefined : "numeric",
  });
}

/**
 * Разбор ссылок на источники внутри текста.
 *
 * Ответ может нести маркеры вида `[1]` — так на утверждение вешается
 * источник. Детерминированный поиск их не ставит: он не знает, какая фраза
 * из какого материала, и расставить номера наугад значило бы выдумать
 * привязку — ровно то, ради чего источники и заведены. Разбор существует
 * заранее, чтобы модель, которая ставить их умеет, не потребовала переделки
 * ленты: появятся маркеры — станут ссылками сразу.
 *
 * Маркер на несуществующий источник остаётся текстом. Ссылка в никуда хуже
 * её отсутствия: по ней нажмут.
 */
function withMarkers(text: string, sources?: Source[]): (string | { note: number })[] {
  if (!sources?.length) return [text];

  const parts: (string | { note: number })[] = [];
  let rest = text;

  for (;;) {
    const found = rest.match(/\[(\d{1,2})\]/);
    if (!found || found.index === undefined) break;

    const number = Number(found[1]);
    parts.push(rest.slice(0, found.index));
    // Номер вне списка источников — не ссылка, а обычный текст.
    parts.push(number >= 1 && number <= sources.length ? { note: number } : found[0]);
    rest = rest.slice(found.index + found[0].length);
  }

  parts.push(rest);
  return parts.filter((part) => part !== "");
}

/**
 * Сколько ждём ответа, прежде чем погасить точки.
 *
 * Страховка, а не срок. Ответ гасит их сам — событием потока; этот предел
 * нужен на случай, когда события не будет вовсе: поток оборвался, портал
 * перезапустился, ответ потерялся. Вечные точки хуже отсутствия точек:
 * человек ждёт того, чего уже не случится.
 *
 * Минута, а не десять секунд: у модели десять секунд — обычный ответ,
 * и погасшие на восьмой секунде точки означали бы «не дождался» ровно там,
 * где всё идёт по плану.
 */
const THINKING_LIMIT = 60_000;

/**
 * Обращение из разговора: контакты и согласие.
 *
 * <p>Спрашивается здесь, а не перед первым сообщением, и это разница между
 * «спросил и ушёл» и «спросил, не дождался, оставил контакты». Посетитель
 * анонимен ровно до этого места: ключ вкладки о человеке не сообщает ничего,
 * и согласие ему давать не на что.
 *
 * <p>Текст обращения не спрашивается: им становится переписка, которая уже
 * состоялась. Просить пересказать в форме то, что человек только что написал
 * в чат, — значит спросить дважды.
 */
function TicketForm({
  callback,
  onSend,
  onCancel,
}: {
  callback: boolean;
  onSend: (lead: ChatLead) => Promise<string | null>;
  onCancel: () => void;
}) {
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  return (
    <form
      className={styles.ticket}
      onSubmit={async (e) => {
        e.preventDefault();
        const data = new FormData(e.currentTarget);
        setSending(true);
        setError(
          await onSend({
            name: String(data.get("name") ?? "").trim(),
            company: String(data.get("company") ?? "").trim(),
            phone: String(data.get("phone") ?? "").trim(),
            email: String(data.get("email") ?? "").trim(),
            consent: Boolean(data.get("consent")),
            callback,
            reason: String(data.get("reason") ?? "").trim(),
            productSlug: String(data.get("productSlug") ?? "").trim(),
          }),
        );
        setSending(false);
      }}
    >
      <p className={styles.ticketTitle}>{callback ? "Заказать обратный звонок" : "Обращение специалисту"}</p>
      {/* Что произойдёт — сказано до того, как человек заполнит поля.
          «Оставьте контакты» без объяснения выглядит как сбор базы. */}
      <p className={styles.ticketNote}>
        Переписка приложится к обращению — пересказывать вопрос не нужно.
        {callback ? "Специалист перезвонит по указанному телефону." : "Номер придёт на почту."}
      </p>

      <input className={styles.ticketField} name="name" placeholder="Имя" required />
      <input
        className={styles.ticketField}
        name="company"
        placeholder="Организация (необязательно)"
      />
      <input
        className={styles.ticketField}
        name="phone"
        type="tel"
        placeholder="Телефон"
        required
      />
      <input
        className={styles.ticketField}
        name="email"
        type="email"
        placeholder={callback ? "Почта (необязательно)" : "Почта"}
        required={!callback}
      />

      <input className={styles.ticketField} name="reason" placeholder="Причина обращения (необязательно)" maxLength={500} />
      <select className={styles.ticketField} name="productSlug" aria-label="Интересующее изделие" defaultValue="">
        <option value="">Изделие не выбрано</option>
        {products.map(product => <option key={product.slug} value={product.slug}>{product.name}</option>)}
      </select>

      <label className={styles.ticketConsent}>
        <input type="checkbox" name="consent" required />
        <span>
          {consentCopy.label} —{" "}
          <a href={consentCopy.href}>{consentCopy.linkLabel}</a>
        </span>
      </label>

      {error && <p className={styles.ticketError}>{error}</p>}

      <div className={styles.ticketButtons}>
        <button type="submit" className={styles.ticketSend} disabled={sending}>
          {sending ? "Отправляем…" : "Отправить обращение"}
        </button>
        <button type="button" className={styles.ticketCancel} onClick={onCancel}>
          Отмена
        </button>
      </div>
    </form>
  );
}

export default function VedalinaChat({ onClose }: { onClose?: () => void }) {
  const [list, setList] = useState<Message[]>([GREETING]);
  // Ведалина считает ответ.
  //
  // Раньше это был флаг, который виджет ставил себе сам на время запроса
  // и снимал по его завершении. Работало, пока ответ приходил в теле того же
  // запроса. Теперь ответ доезжает потоком, а значит ожидание переживает
  // и перезагрузку страницы, и вторую вкладку — и знает о нём портал,
  // а не только это окно.
  const [typing, setTyping] = useState(false);
  // Кусок ещё не дописанного ответа. Показывается как незаконченный и
  // заменяется лентой, как только ответ записан. Сам по себе он не значит
  // ничего: в базе его нет.
  const [answerDraft, setAnswerDraft] = useState("");
  const [stage, setStage] = useState("");
  // Голоса в чате нет: ни записи, ни озвучивания ответа. Решение заказчика
  // 15 сентября — «не для этого проекта фича». Бэкенд голоса не тронут,
  // но окно к нему больше не обращается.
  const [draft, setDraft] = useState("");
  // Кнопки приходят с портала: подпись и заготовка, разложенные по двум
  // местам, расходятся на первой же правке — и расходятся молча.
  const [prompts, setPrompts] = useState<Prompt[]>([]);
  // Разговор ждёт человека. Состояние, а не сообщение в ленте: сообщение
  // дописывалось после ответа и пропадало на первом же обновлении ленты
  // из потока — то есть исчезало ровно тогда, когда посетитель ждал.
  const [waiting, setWaiting] = useState(false);
  // Номер обращения, заведённого из этого разговора. Приходит с лентой:
  // человек, вернувшийся через неделю, обязан найти его там же, где оставил.
  const [leadNumber, setLeadNumber] = useState<string | null>(null);
  // Форма обращения раскрыта. Не отдельный экран: разговор остаётся на месте,
  // и видно, из чего обращение заводится.
  const [ticketForm, setTicketForm] = useState(false);
  const [callback, setCallback] = useState(false);
  // Отвечают ли сейчас люди. Приходит с лентой и меняется событием потока:
  // сотрудник, открывший админку, появляется на связи не тогда, когда
  // посетитель обновит страницу.
  //
  // Пока портал не ответил — null: «неизвестно» это не «оффлайн». Надпись
  // «сейчас никого нет», показанная до первого ответа портала, была бы
  // догадкой, и первое, что увидел бы посетитель, — сообщение о том,
  // что писать некому.
  const [support, setSupport] = useState<ChatSupport | null>(null);
  // Подсказка о часах работы раскрыта.
  const [hoursOpen, setHoursOpen] = useState(false);
  // Когда виджет последний раз был открыт на глазах у человека. Снимается
  // один раз при открытии: обновляйся оно на каждое сообщение — граница
  // «новые» исчезала бы ровно в тот момент, когда она нужна.
  const [seenAt] = useState(() => (typeof window === "undefined" ? 0 : lastSeen()));
  const feed = useRef<HTMLDivElement>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const visitor = useRef<string>("");
  // Сотрудник печатает. Живёт секунды и гаснет само: события «перестал»
  // не существует, человек волен просто закрыть вкладку.
  const [staffTyping, setStaffTyping] = useState(false);
  const fade = useRef<ReturnType<typeof setTimeout> | null>(null);
  // Предел ожидания ответа Ведалины: страховка от вечных точек.
  const patience = useRef<ReturnType<typeof setTimeout> | null>(null);
  // Когда последний раз сообщали, что посетитель печатает.
  const pinged = useRef(0);

  // Таймер ответа сбрасываем и при новом вопросе, и при размонтировании —
  // иначе быстрые клики по чипам наложат несколько ответов друг на друга.
  useEffect(() => () => {
    if (timer.current) clearTimeout(timer.current);
  }, []);

  // Отметка «виджет был открыт» ставится при закрытии окна, а не при
  // открытии: поставленная сразу, она стёрла бы границу «новые» в тот же
  // миг, когда человек её увидел.
  //
  // Сюда же попадает уход со страницы: вкладку закрывают чаще, чем
  // сворачивают чат, и без этого граница осталась бы от позапрошлого раза.
  useEffect(() => {
    const remember = () => rememberSeen(Date.now());
    document.addEventListener("visibilitychange", remember);
    return () => {
      document.removeEventListener("visibilitychange", remember);
      remember();
    };
  }, []);

  // Разговор продолжается между страницами и перезагрузками: ключ вкладки
  // лежит в браузере, лента подтягивается при открытии виджета.
  //
  // Подписка на поток — обычный EventSource: дверь публичная, токена не надо,
  // и переподключение при обрыве браузер делает сам. Благодаря ей ответ
  // сотрудника появляется у посетителя без перезагрузки страницы.
  useEffect(() => {
    if (!apiConfigured) return;
    visitor.current = visitorKey();
    let alive = true;

    void chatPrompts().then((loaded) => {
      if (alive) setPrompts(loaded);
    });

    const refresh = () =>
      void chatThread(visitor.current).then((thread) => {
        if (!alive || !thread) return;

        // Про людей портал отвечает всегда — и когда разговора ещё нет.
        // Виджет открывают до первого сообщения, и надпись в шапке нужна
        // ему уже тогда.
        if (thread.support) setSupport(thread.support);

        if (!thread.messages.length) return;

        // Точки гасит лента, а не таймер: пришедшая лента и есть ответ
        // на вопрос «дождались ли». Портал сообщает в ней же, думает ли
        // Ведалина прямо сейчас, — и это переживает перезагрузку страницы.
        thinking(thread.answering);

        // Черновик своё отработал: дальше на экране настоящая лента.
        // Оставить его рядом с записанным ответом значит показать текст
        // дважды.
        setAnswerDraft("");

        setWaiting(thread.status === "waiting");
        setLeadNumber(thread.leadNumber ?? null);
        setList([GREETING, ...thread.messages.map(toMessage)]);
      });

    refresh();

    const url = chatStreamUrl(visitor.current);
    if (!url) return;
    const stream = new EventSource(url);

    // Слушать надо ИМЕНОВАННОЕ событие, а не onmessage.
    //
    // onmessage срабатывает только на события без поля event, а портал шлёт
    // `event:changed`. Обработчик не вызывался ни разу: лента читалась один
    // раз при открытии и дальше не менялась. Снаружи это выглядело так,
    // будто ответ сотрудника и галочка «прочитано» появляются только после
    // перезагрузки страницы — что и происходило.
    //
    // Соседний обработчик typing работал именно потому, что подписан
    // по имени.
    stream.addEventListener("changed", refresh);

    // Кто-то печатает. Отдельный вид события, потому что перечитывать ленту
    // здесь незачем: в базе этого факта нет и не будет.
    stream.addEventListener("typing", (event) => {
      try {
        const parsed = JSON.parse((event as MessageEvent).data) as { who: string };
        if (!alive) return;

        // Ведалина взялась за ответ. Приходит из портала, а не ставится
        // виджетом по факту отправки: вопрос мог быть задан в другой вкладке.
        if (parsed.who === "assistant") {
          thinking(true);
          return;
        }

        if (parsed.who !== "staff") return;
        setStaffTyping(true);
        if (fade.current) clearTimeout(fade.current);
        // Гаснет по таймеру, и иначе нельзя: события «перестал печатать»
        // не существует — человек волен просто закрыть вкладку.
        fade.current = setTimeout(() => setStaffTyping(false), 5000);
      } catch {
        // Событие незнакомого вида — не повод рвать поток.
      }
    });

    // Специалист появился на связи или ушёл. Событие редкое — портал шлёт
    // его на переходах, а не на каждой открытой вкладке админки, — и ленту
    // по нему перечитывать незачем: о самом разговоре здесь не сказано ничего.
    stream.addEventListener("presence", (event) => {
      try {
        const parsed = JSON.parse((event as MessageEvent).data) as { online: boolean };
        if (!alive) return;
        // Часы работы остаются прежними: меняется присутствие, а не расписание.
        setSupport((was) => (was ? { ...was, online: parsed.online } : was));
      } catch {
        // Событие незнакомого вида — не повод рвать поток.
      }
    });

    stream.addEventListener("stage", (event) => {
      try {
        const parsed = JSON.parse((event as MessageEvent).data) as { stage: string };
        const labels: Record<string, string> = { searching: "Ищу в материалах", documents: "Сверяю документы", composing: "Формулирую ответ" };
        if (alive && labels[parsed.stage]) { setStage(labels[parsed.stage]); thinking(true); }
      } catch { /* A malformed event must not break text chat. */ }
    });

    // Кусок ответа, который ещё пишется. Единственное событие с текстом:
    // оно уходит только на этот ключ и повторится лентой через секунду.
    stream.addEventListener("draft", (event) => {
      try {
        const parsed = JSON.parse((event as MessageEvent).data) as { chunk: string };
        if (!alive || !parsed.chunk) return;
        thinking(true);
        setAnswerDraft((was) => was + parsed.chunk);
      } catch {
        // Битый кусок — не повод рвать поток и не повод показывать мусор.
      }
    });

    return () => {
      alive = false;
      stream.close();
      if (fade.current) clearTimeout(fade.current);
      if (patience.current) clearTimeout(patience.current);
    };
  }, []);

  /**
   * Зажечь или погасить точки Ведалины.
   *
   * Зажигая, заводим предел ожидания. Без него точки остаются навсегда,
   * если ответ не доедет вовсе: поток оборвался, портал перезапустился.
   * Человек в этом случае ждёт того, чего уже не будет, — и уходит,
   * решив, что чат сломан.
   */
  function thinking(on: boolean) {
    setTyping(on);
    if (patience.current) clearTimeout(patience.current);
    if (!on) { setStage(""); return; }
    patience.current = setTimeout(() => {
      setTyping(false);
      setAnswerDraft("");
    }, THINKING_LIMIT);
  }

  function ask(text: string, intent?: string) {
    const question = text.trim();
    if (!question) return;
    if (apiConfigured && /^(?:позвоните|перезвоните)(?:\s+мне)?[.!?]*$/i.test(question)) {
      setCallback(true);
      setTicketForm(true);
      setDraft("");
      return;
    }

    if (timer.current) clearTimeout(timer.current);
    setList((prev) => [...prev, { from: "me", text: question }]);
    setDraft("");
    setStage("");
    thinking(true);

    // Без адреса API отвечаем локально: так чат работает в режиме вёрстки,
    // когда серверная часть не поднята.
    if (!apiConfigured) {
      timer.current = setTimeout(() => {
        setList((prev) => [...prev, { from: "bot", text: answerFor(question) }]);
        thinking(false);
      }, vedalina.replyDelay);
      return;
    }

    void sayInChat(visitor.current, question, intent).then((thread) => {
      if ("error" in thread) {
        thinking(false);
        // Портал молчит — отдаём живые контакты, а не оставляем тупик.
        setList((prev) => [
          ...prev,
          {
            from: "bot",
            text: thread.error,
            handoff: { reason: thread.error, phone: site.phone, email: site.email, forms: [] },
          },
        ]);
        return;
      }

      // Лента целиком: порядок сообщений определяет сервер, и «ответ на
      // позапрошлый вопрос» здесь взяться неоткуда.
      setList([GREETING, ...thread.messages.map(toMessage)]);

      // Точки НЕ гасим по возврату запроса — в нём ответа больше нет.
      //
      // Дверь принимает вопрос и отвечает сразу, а ответ доезжает потоком:
      // модель считает секундами, и ждать её внутри запроса значит держать
      // окно неподвижным, а поток обслуживания — занятым. Гасит точки лента,
      // пришедшая по событию, или предел ожидания.
      //
      // Исключение — нажатая кнопка: её текст известен заранее и приходит
      // сразу, и тогда портал не берётся считать ничего.
      thinking(thread.answering || lastIsVisitors(thread.messages));

      // Ответа могло не быть вовсе — тогда разговор ждёт человека.
      // Придумывать ответ запрещено правилами ассистента.
      setWaiting(thread.status === "waiting");
    });
  }

  /**
   * Оценить ответ Ведалины.
   *
   * Состояние приходит с портала лентой, а не ставится здесь: нажатие,
   * нарисованное на месте и не сохранившееся, — это оценка, которую никто
   * не увидит, при том что человек уверен, что сказал своё.
   */
  function rate(messageId: string, helpful: boolean) {
    void rateAnswer(visitor.current, messageId, helpful).then((thread) => {
      if ("error" in thread) return;
      setList([GREETING, ...thread.messages.map(toMessage)]);
    });
  }

  /**
   * Отправить обращение.
   *
   * @return текст ошибки для показа в форме или `null`, если приняли.
   *         Ошибка возвращается, а не рисуется здесь: показать её обязана
   *         форма, рядом с кнопкой, которую нажали.
   */
  async function sendTicket(lead: ChatLead): Promise<string | null> {
    if (!apiConfigured) return "Портал недоступен: обращение не отправлено.";

    // The form can be opened before the first message. Start the conversation
    // only on submission, after the visitor explicitly supplied consent.
    if (!lead.consent) return "Без согласия отправить обращение нельзя.";
    const existing = await chatThread(visitor.current);
    if (!existing?.id) {
      const started = await sayInChat(visitor.current, lead.callback ? "Позвоните мне" : "Хочу оставить обращение", "quote");
      if ("error" in started) return started.error;
    }
    const result = await raiseChatLead(visitor.current, lead);
    if ("error" in result) {
      // Разбор по полям приходит от портала; в узком окне чата показываем
      // первую ошибку, а не список: список из пяти строк вытеснит переписку.
      const first = result.fields ? Object.values(result.fields)[0] : null;
      return first ?? result.error;
    }

    setLeadNumber(result.number);
    setTicketForm(false);
    // Сообщение о номере портал уже дописал в ленту — она приедет событием.
    // Перечитываем сами на случай, если поток оборвался: номер обязан
    // оказаться на экране, он единственное, что человек унесёт с собой.
    void chatThread(visitor.current).then((thread) => {
      if (thread?.messages.length) {
        setList([GREETING, ...thread.messages.map(toMessage)]);
        setWaiting(thread.status === "waiting");
        thinking(thread.answering);
      }
    });
    return null;
  }

  /**
   * Последнее слово за посетителем — значит ответа ещё нет.
   *
   * Признак `answering` приходит из портала и точен, но между приёмом вопроса
   * и тем мигом, когда портал взялся считать, проходит мгновение: слушатель
   * запускается после записи. Попади ответ двери ровно в эту щель — точки
   * не зажглись бы вовсе, и окно на секунду выглядело бы так, будто вопрос
   * пропал.
   */
  function lastIsVisitors(messages: ChatLine[]): boolean {
    return messages.length > 0 && messages[messages.length - 1].author === "visitor";
  }

  /**
   * Позвать живого человека.
   *
   * Отдельная дверь, а не сообщение с особым текстом. Кнопка «Специалист
   * VEDAL» раньше отправляла свою подпись как вопрос — поиск отвечал на неё
   * каталогом, а разговор оставался у Ведалины: никого не звали.
   */
  function human() {
    if (waiting) return;

    if (!apiConfigured) {
      setList((prev) => [
        ...prev,
        {
          from: "bot",
          text: vedalina.handoffNote,
          handoff: { reason: vedalina.handoffNote, phone: site.phone, email: site.email, forms: [] },
        },
      ]);
      return;
    }

    thinking(true);
    void callHuman(visitor.current).then((thread) => {
      thinking(false);
      if ("error" in thread) {
        // Портал молчит — отдаём живые контакты, а не оставляем тупик.
        setList((prev) => [
          ...prev,
          {
            from: "bot",
            text: thread.error,
            handoff: { reason: thread.error, phone: site.phone, email: site.email, forms: [] },
          },
        ]);
        return;
      }
      setList([GREETING, ...thread.messages.map(toMessage)]);
      setWaiting(thread.status === "waiting");
    });
  }

  // Лента целиком, а не последние несколько сообщений. Окно в четыре
  // реплики съедало разговор на третьем вопросе: посетитель терял и свой
  // вопрос, и ссылки из ответа. Панель прокручивается — места хватает.
  const shown = list;

  // Без портала кнопки берутся из содержимого: иначе в режиме вёрстки,
  // когда серверная часть не поднята, окно остаётся вовсе без кнопок
  // и выглядит сломанным, а не ненастроенным.
  const shownPrompts: Prompt[] = prompts.length
    ? prompts
    : quickReplies.map((label) => ({
        intent: label,
        label,
        action: label === "Позвать специалиста" ? "handoff" : "ask",
      }));

  // Прокрутка вниз на каждое изменение: новое сообщение, появившееся ниже
  // видимой части, для посетителя не появилось вовсе.
  useEffect(() => {
    const box = feed.current;
    if (box) box.scrollTop = box.scrollHeight;
  }, [shown, typing, answerDraft, staffTyping, waiting]);

  return (
    <section className={`${styles.chat} ym-hide-content`} aria-label={`Чат с ассистентом ${vedalina.name}`}>
      <div className={styles.head}>
        {/* Та же фактура, что на тёмных полосах страниц: чат — часть сайта,
            а не вставленный чужой виджет. Без квадратов и на 9%. */}
        <LivePattern variant={2} tone="dark" />

        {/* Размер пропами, а не fill — см. VedalinaWidget: fill выставляет
            картинке inset: 0, и обёртка перестаёт управлять её размером.

            Просим вдвое больше, чем занимает круг: кадр немного увеличен
            маской, и картинка ровно по размеру круга при этом мылит.
            Отображаемый размер держит CSS.

            Точка «на связи» — снаружи круга: он обрезает содержимое ради
            кадра, и точка на краю превращалась в бледный серп. */}
        <div className={styles.avatarSlot}>
          <div className={styles.avatarWrap}>
            <Image
              className={styles.avatar}
              src={vedalina.avatar}
              alt={`Портрет ассистента ${vedalina.name}`}
              width={88}
              height={88}
            />
          </div>
          <span className={styles.status} aria-hidden="true" />
        </div>
        <div>
          <div className={styles.name}>{vedalina.name}</div>
          <div className={styles.role}>{vedalina.role}</div>

          {/* Кто на связи — про людей, а не про Ведалину: она отвечает всегда.
              Надпись говорит о факте (открыто ли рабочее место), а не о часах
              работы: «мы онлайн» по расписанию врёт в обеденный перерыв ровно
              тому, кто на неё понадеялся.

              Пока портал не ответил, надписи нет вовсе: «неизвестно» — это
              не «оффлайн», и встречать посетителя сообщением, что писать
              некому, было бы догадкой. */}
          {support && (
            <button
              type="button"
              className={support.online ? styles.presenceOn : styles.presenceOff}
              aria-expanded={hoursOpen}
              onClick={() => setHoursOpen((was) => !was)}
            >
              <span className={styles.presenceDot} aria-hidden="true" />
              {support.online ? "Специалист на связи" : "Специалисты офлайн"}
            </button>
          )}
        </div>
        {/* Одна кнопка, а не две. Рядом стояла «−» с подписью «Свернуть чат»,
            и обе вызывали один и тот же `onClose`: разговор в любом случае
            сохранялся, окно в любом случае сворачивалось в кнопку-запускатель
            (issue #106). Две кнопки, обещающие разное и делающие одно, — это
            выбор, которого нет.

            Второе поведение — «закрыть» как «завершить разговор» — не
            выдумано: такой двери у портала нет, а придумывать её здесь
            значит обещать от имени системы то, чего она не делает. */}
        {onClose && (
          <div className={styles.headTools}>
            <button
              type="button"
              className={styles.headButton}
              onClick={onClose}
              aria-label="Закрыть чат"
            >
              ×
            </button>
          </div>
        )}
      </div>

      {/* Подсказка о часах работы. Раскрывается нажатием, а не всплывает
          по наведению: `title` браузера на телефоне не показывается вовсе,
          а именно там посетитель чаще всего и оказывается вечером.

          Держится под шапкой, а не в ленте: это свойство чата, а не реплика
          в разговоре, и прокруткой оно уезжать не должно. */}
      {support && hoursOpen && (
        <p className={styles.hours} aria-live="polite">
          Специалисты отвечают {support.hours}.{" "}
          {support.online
            ? "Сейчас кто-то на связи — ответит в этом окне."
            : support.openNow
              ? "Сейчас на связи никого нет. Можно писать здесь — прочитают, когда вернутся, — или оставить обращение: у него будет номер, и ответ придёт на почту."
              : "Сейчас нерабочее время. Можно писать здесь — прочитают утром, — или оставить обращение: у него будет номер, и ответ придёт на почту."}
        </p>
      )}

      <div className={styles.feed} aria-live="polite" ref={feed}>
        {shown.map((m, i) => (
          <Fragment key={`${m.from}-${i}-${m.text.slice(0, 12)}`}>
            {/* Разделитель дня. Разговор возвращаются читать через час
                и через неделю, и без него вчерашний ответ выглядит как
                написанный только что. */}
            {m.at && day(m.at) !== day(shown[i - 1]?.at) && (
              <p className={styles.day}>{dayLabel(m.at)}</p>
            )}

            {/* Граница нового. Считается по времени, когда виджет последний
                раз был открыт на глазах у человека, а не по отметке
                «прочитано»: ту портал ставит в момент, когда отдаёт ленту,
                — то есть к приходу она уже проставлена, и непрочитанного
                в ней не бывает по устройству. */}
            {m.at && isFirstUnseen(shown, i, seenAt) && (
              <p className={styles.fresh}>Новые сообщения</p>
            )}

            <div
              className={`${styles.turn} ${m.from === "me" ? styles.turnMe : styles.turnBot}`}
            >
              {/* Подпись только у сотрудника. Посетитель должен видеть, что
                  отвечает человек, а не машина: у Ведалины подпись есть в шапке
                  окна, у самого посетителя она бессмысленна. */}
              {m.from === "staff" && <span className={styles.who}>{m.who}</span>}

              <p className={`${styles.msg} ${styles[m.from]}`}>
                {withMarkers(m.text, m.sources).map((part, at) =>
                  typeof part === "string" ? (
                    part
                  ) : (
                    // Маркер источника внутри текста — ссылка на материал,
                    // а не значок. Нажимают именно на него.
                    <a
                      key={at}
                      className={styles.note}
                      href={m.sources![part.note - 1].url}
                      title={m.sources![part.note - 1].title}
                    >
                      {part.note}
                    </a>
                  ),
                )}
              </p>

              {/* Время и отметка доставки. Галочки, а не слово: слово
                  «прочитано» занимает строку, а отметка стоит рядом со
                  временем и читается одним взглядом.

                  Одна галочка — сообщение у портала, две — его увидел живой
                  человек. Различать обязательно: ждущему важно, дошло ли оно
                  до людей, а не до сервера. */}
              {(m.at || m.readAt) && (
                <span className={styles.meta}>
                  {clock(m.at)}
                  {m.from === "me" && (
                    <span
                      className={m.readAt ? styles.readTwice : styles.readOnce}
                      title={m.readAt ? "Прочитано" : "Доставлено"}
                      aria-label={m.readAt ? "Прочитано" : "Доставлено"}
                    >
                      {m.readAt ? "✓✓" : "✓"}
                    </span>
                  )}
                </span>
              )}

              {/* Ответ обязан нести ссылки на источники: правило из спеки
                  ассистента. Без них утверждение проверить нечем.

                  Нумерованный, а не маркированный: номера — те же, что
                  в маркерах внутри текста, и список без них оставил бы
                  маркеры ни на что не указывающими. */}
              {m.sources && (
                <ol className={styles.sources}>
                  {m.sources.map((s) => (
                    <li key={s.url}>
                      <a href={s.url}>{s.title}</a>
                    </li>
                  ))}
                </ol>
              )}

              {m.from === "bot" && m.id && m.actions?.length ? (
                <ChatNavigation actions={m.actions} visitor={visitor.current} messageId={m.id} />
              ) : null}

              {/* Помог ли ответ. Спрашивается только у Ведалины: «специалист
                  не помог» — это не оценка ответа, а жалоба на человека,
                  и разбирать её кнопкой в чате нельзя. */}
              {m.from === "bot" && m.id && apiConfigured && (
                <span className={styles.rate}>
                  <button
                    type="button"
                    className={m.helpful === true ? styles.rateOn : styles.rateButton}
                    aria-pressed={m.helpful === true}
                    aria-label="Ответ помог"
                    onClick={() => rate(m.id!, true)}
                  >
                    ↑
                  </button>
                  <button
                    type="button"
                    className={m.helpful === false ? styles.rateOn : styles.rateButton}
                    aria-pressed={m.helpful === false}
                    aria-label="Ответ не помог"
                    onClick={() => rate(m.id!, false)}
                  >
                    ↓
                  </button>

                  {/* Нажавшему «не помог» надо дать выход, а не поблагодарить
                      за отзыв: он остался без ответа, и это единственное,
                      что его сейчас занимает. */}
                  {m.helpful === false && !waiting && (
                    <button type="button" className={styles.rateHuman} onClick={human}>
                      Позвать специалиста
                    </button>
                  )}
                </span>
              )}

              {m.handoff && (
                <p className={styles.handoff}>
                  <a href={`tel:${m.handoff.phone.replace(/\s/g, "")}`}>{m.handoff.phone}</a>
                  {" · "}
                  <a href={`mailto:${m.handoff.email}`}>{m.handoff.email}</a>
                </p>
              )}
            </div>
          </Fragment>
        ))}

        {/* Ответ, который ещё пишется. Показывается вместо точек, как только
            приехал первый кусок: текст, появляющийся на глазах, — это ответ
            на вопрос «работает ли вообще», которого точки не дают.

            Курсор в конце обязателен. Без него незаконченный ответ выглядит
            как законченный, и посетитель уходит читать дальше на середине
            фразы. */}
        {answerDraft && (
          <p
            className={`${styles.msg} ${styles.bot} ${styles.answerDraft}`}
            aria-live="polite"
            aria-busy="true"
          >
            {answerDraft}
            <span className={styles.caret} aria-hidden="true" />
          </p>
        )}

        {typing && stage && <p role="status" className={styles.stageNote}>{stage}…</p>}
        {typing && !answerDraft && (
          <p className={`${styles.msg} ${styles.bot} ${styles.typing}`} aria-label="Ведалина печатает">
            <span />
            <span />
            <span />
          </p>
        )}

        {/* Отвечает человек — и это надо сказать словом, а не теми же точками,
            что у Ведалины. Разница между «машина думает» и «специалист пишет»
            для ждущего посетителя существенная: во втором случае он готов
            подождать дольше. */}
        {staffTyping && (
          <p className={`${styles.msg} ${styles.staff} ${styles.staffTyping}`} aria-live="polite">
            Специалист печатает…
          </p>
        )}

        {/* Разговор ждёт человека. Полоса, а не сообщение в ленте: сообщение
            дописывалось после ответа и пропадало на первом же обновлении
            из потока — исчезало ровно тогда, когда посетитель ждал. */}
        {waiting && (
          <p className={styles.waiting} aria-live="polite">
            <span className={styles.waitingDot} aria-hidden="true" />
            {/* Ждать до утра и ждать десять минут — разные вещи, и говорить
                о них одинаково нельзя: «ответит в этом окне» в полночь человек
                прочтёт как «сейчас ответят», закроет вкладку и решит, что чат
                не работает. */}
            {support && !support.online
              ? `Ждём специалиста. На связи сейчас никого нет — отвечают ${support.hours}. Написанное здесь прочитают: `
              : "Ждём специалиста. Он ответит в этом окне — можно писать дальше, он прочитает всё. Не хотите ждать: "}
            <a href={`tel:${site.phone.replace(/\s/g, "")}`}>{site.phone}</a>
            {" · "}
            <a href={`mailto:${site.email}`}>{site.email}</a>
          </p>
        )}

        {/* Обращение заведено. Плашка держится в ленте, а не проговаривается
            один раз сообщением: номер — единственное, что человек унесёт
            с собой, и искать его прокруткой через неделю он не станет.

            Ссылки «перейти к обращению» здесь нет намеренно: личного кабинета
            у посетителя нет, вести ей некуда, а придумать её значит обещать
            страницу, которой не существует. */}
        {leadNumber && (
          <p className={styles.ticketBadge} aria-live="polite">
            Обращение <b>{leadNumber}</b> · принято
          </p>
        )}

        {/* Форма обращения раскрывается прямо в ленте: разговор остаётся
            на месте, и видно, из чего обращение заводится. */}
        {ticketForm && !leadNumber && (
          <TicketForm callback={callback} onSend={sendTicket} onCancel={() => setTicketForm(false)} />
        )}

        {/* Позвать человека можно и не дожидаясь, пока Ведалина не найдёт
            ответа. Кнопка стоит и в ожидании: ждущий специалиста — первый,
            кому обращение и нужно, а разговор он мог начать в нерабочее
            время. */}
        {!ticketForm && !leadNumber && apiConfigured && (
          <button
            type="button"
            className={styles.ticketOpen}
            onClick={() => { setCallback(false); setTicketForm(true); }}
            data-analytics="vedalina_ticket_open"
          >
            Создать обращение
          </button>
        )}

        {!ticketForm && !leadNumber && apiConfigured && (
          <button type="button" className={styles.ticketOpen}
            onClick={() => { setCallback(true); setTicketForm(true); }}>
            Позвоните мне
          </button>
        )}

        {/* Кнопки молчат, когда в разговоре человек: заготовка поверх
            живого специалиста выглядит как сотрудник, который не читает,
            что ему пишут. */}
        {!waiting && (
          <div className={styles.chips}>
            {shownPrompts.map((p) => (
              <button
                key={p.intent}
                type="button"
                className={p.action === "handoff" ? styles.chipHuman : styles.chip}
                onClick={() => (p.action === "handoff" ? human() : ask(p.label, p.intent))}
                data-analytics="vedalina_quick_action_click"
              >
                {p.label}
              </button>
            ))}
          </div>
        )}
      </div>

      <form
        className={styles.inputRow}
        onSubmit={(e) => {
          e.preventDefault();
          ask(draft);
        }}
      >
        <input
          className={styles.input}
          value={draft}
          onChange={(e) => {
            setDraft(e.target.value);
            // Не на каждую букву: раз в три секунды, пока поле не пустое.
            if (e.target.value.trim() && apiConfigured) {
              const now = Date.now();
              if (now - pinged.current >= 3000) {
                pinged.current = now;
                pingTyping(visitor.current);
              }
            }
          }}
          placeholder={vedalina.placeholder}
          aria-label={`Сообщение ассистенту ${vedalina.name}`}
        />
        <button type="submit" className={styles.send} aria-label="Отправить">
          <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path
              d="M2 8h11M9 4l4 4-4 4"
              stroke="currentColor"
              strokeWidth="1.8"
              strokeLinecap="square"
            />
          </svg>
        </button>
      </form>

      <p className={styles.disclaimer}>{vedalina.disclaimer}</p>
    </section>
  );
}
