const CONTACT = {
  whatsappNumber: "34655309861",
  phone: "+34655309861",
  email: "xemiruiz@gmail.com"
};

const state = {
  lang: "es",
  kb: "A",
  cart: [],
  i18n: null
};

const refs = {
  kbSelector: document.getElementById("kbSelector"),
  langToggle: document.getElementById("langToggle"),
  chatMessages: document.getElementById("chatMessages"),
  chatForm: document.getElementById("chatForm"),
  chatInput: document.getElementById("chatInput"),
  typingStatus: document.getElementById("typingStatus"),
  cartPanel: document.getElementById("cartPanel"),
  cartItems: document.getElementById("cartItems"),
  cartTotal: document.getElementById("cartTotal"),
  clearCartBtn: document.getElementById("clearCartBtn"),
  mobileCartFab: document.getElementById("mobileCartFab"),
  whatsappBtn: document.getElementById("whatsappBtn"),
  emailBtn: document.getElementById("emailBtn"),
  phoneBtn: document.getElementById("phoneBtn"),
  sendProposalWhatsapp: document.getElementById("sendProposalWhatsapp"),
  sendProposalEmail: document.getElementById("sendProposalEmail"),
  shareBtn: document.getElementById("shareBtn"),
  shareModal: document.getElementById("shareModal"),
  closeShareModal: document.getElementById("closeShareModal"),
  qrImage: document.getElementById("qrImage"),
  serviceCards: document.getElementById("serviceCards")
};

init();

async function init() {
  bindEvents();
  await setLanguage("es");
  renderCart();
  updateContactLinks();
  appendMessage("bot", buildWelcomeMessage());
}

function bindEvents() {
  refs.kbSelector.addEventListener("change", () => {
    state.kb = refs.kbSelector.value;
    appendMessage("bot", `${state.i18n.kbSwitched.replace("{kbName}", getKbName(state.kb))}\n${buildWelcomeMessage()}`);
  });

  refs.langToggle.addEventListener("click", async () => {
    const next = state.lang === "es" ? "en" : "es";
    await setLanguage(next);
    appendMessage("meta", state.i18n.langSwitched);
  });

  refs.chatForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    const message = refs.chatInput.value.trim();
    if (!message) return;
    refs.chatInput.value = "";
    await sendMessage(message);
  });

  refs.clearCartBtn.addEventListener("click", () => {
    state.cart = [];
    renderCart();
    updateContactLinks();
    appendMessage("meta", state.i18n.cartCleared);
  });

  refs.mobileCartFab.addEventListener("click", () => {
    refs.cartPanel.classList.toggle("open");
  });

  refs.sendProposalWhatsapp.addEventListener("click", () => {
    window.open(buildWhatsappLink(true), "_blank", "noopener,noreferrer");
  });

  refs.sendProposalEmail.addEventListener("click", () => {
    window.location.href = buildMailtoLink();
  });

  refs.shareBtn.addEventListener("click", () => {
    const currentUrl = window.location.href;
    refs.qrImage.src = `https://api.qrserver.com/v1/create-qr-code/?size=180x180&data=${encodeURIComponent(currentUrl)}`;
    refs.shareModal.showModal();
  });

  refs.closeShareModal.addEventListener("click", () => {
    refs.shareModal.close();
  });

  window.addEventListener("resize", () => {
    if (!isMobile()) {
      refs.cartPanel.classList.remove("open");
    }
  });
}

async function setLanguage(lang) {
  state.lang = lang;
  const response = await fetch(`./i18n/${lang}.json`);
  state.i18n = await response.json();
  refs.langToggle.textContent = lang === "es" ? "EN" : "ES";
  document.documentElement.lang = lang;
  applyTranslations();
  renderServiceCards();
  renderCart();
  updateContactLinks();
}

function applyTranslations() {
  document.querySelectorAll("[data-i18n]").forEach((node) => {
    const key = node.getAttribute("data-i18n");
    if (state.i18n[key]) node.textContent = state.i18n[key];
  });

  document.querySelectorAll("[data-i18n-placeholder]").forEach((node) => {
    const key = node.getAttribute("data-i18n-placeholder");
    if (state.i18n[key]) node.setAttribute("placeholder", state.i18n[key]);
  });
}

function renderServiceCards() {
  refs.serviceCards.innerHTML = "";
  state.i18n.serviceCards.forEach((item) => {
    const card = document.createElement("article");
    card.className = "card";
    card.innerHTML = `<strong>${item.title}</strong><p>${item.body}</p>`;
    refs.serviceCards.appendChild(card);
  });
}

async function sendMessage(message) {
  appendMessage("user", message);
  refs.typingStatus.classList.remove("hidden");

  try {
    const response = await fetch(`${window.APP_CONFIG.API_BASE_URL}/api/chat`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        kb: state.kb,
        message,
        cart: state.cart,
        lang: state.lang
      })
    });

    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`);
    }

    const data = await response.json();
    if (Array.isArray(data.actions) && data.actions.length > 0) {
      applyActions(data.actions, data.item);
    }

    appendMessage("bot", data.reply || state.i18n.fallbackReply);
  } catch (error) {
    appendMessage("bot", `${state.i18n.serverError} (${error.message})`);
  } finally {
    refs.typingStatus.classList.add("hidden");
  }
}

function applyActions(actions, item) {
  actions.forEach((action) => {
    const type = (action.type || "").toUpperCase();
    if (type === "ADD" && item) {
      const existing = state.cart.find((entry) => entry.id === item.id);
      if (existing) {
        existing.qty += 1;
      } else {
        state.cart.push({ ...item, qty: 1 });
      }
    }

    if (type === "REMOVE") {
      const itemId = action.itemId || (item && item.id);
      if (!itemId) return;
      const idx = state.cart.findIndex((entry) => entry.id === itemId);
      if (idx >= 0) {
        if (state.cart[idx].qty > 1) {
          state.cart[idx].qty -= 1;
        } else {
          state.cart.splice(idx, 1);
        }
      }
    }

    if (type === "CLEAR") {
      state.cart = [];
    }
  });

  renderCart();
  updateContactLinks();
}

function renderCart() {
  refs.cartItems.innerHTML = "";
  if (state.cart.length === 0) {
    const empty = document.createElement("p");
    empty.className = "muted";
    empty.textContent = state.i18n ? state.i18n.emptyCart : "";
    refs.cartItems.appendChild(empty);
    refs.cartTotal.textContent = formatCurrency(0);
    return;
  }

  let total = 0;
  state.cart.forEach((item) => {
    const unit = parsePrice(item.price);
    total += unit * (item.qty || 1);

    const row = document.createElement("div");
    row.className = "cart-item";
    row.innerHTML = `
      <strong>${item.title}</strong>
      <small>${item.id} | ${item.type || ""}</small>
      <small>${item.price} x ${item.qty || 1}</small>
    `;
    refs.cartItems.appendChild(row);
  });

  refs.cartTotal.textContent = formatCurrency(total);
}

function parsePrice(raw) {
  if (!raw) return 0;
  const cleaned = String(raw)
    .replace(/[^\d,.-]/g, "")
    .replace(/\./g, "")
    .replace(",", ".");
  const parsed = Number.parseFloat(cleaned);
  return Number.isFinite(parsed) ? parsed : 0;
}

function formatCurrency(value) {
  const locale = state.lang === "es" ? "es-ES" : "en-US";
  return new Intl.NumberFormat(locale, { style: "currency", currency: "EUR", maximumFractionDigits: 0 }).format(value);
}

function cartSummaryText() {
  if (state.cart.length === 0) {
    return state.i18n.emptyCart;
  }

  const lines = state.cart.map((item) => `- ${item.title} (${item.id}) x${item.qty || 1} | ${item.price}`);
  return `${lines.join("\n")}\n${state.i18n.estimatedTotal}: ${refs.cartTotal.textContent}`;
}

function buildWhatsappLink(isProposal) {
  const selectedCompany = getKbName(state.kb);
  const base = state.lang === "es"
    ? `Hola, he visto la demo de Nébula Sur y me interesa ampliar informacion de ${selectedCompany}. ¿Podemos agendar una reunion? Mi empresa es: ____`
    : `Hi, I saw the Nébula Sur demo and I want more information about ${selectedCompany}. Can we schedule a meeting? My company is: ____`;

  const header = isProposal
    ? state.i18n.proposalIntro
    : state.i18n.contactIntro;

  const text = `${base}\n\n${header}\n${cartSummaryText()}`;
  return `https://wa.me/${CONTACT.whatsappNumber}?text=${encodeURIComponent(text)}`;
}

function buildMailtoLink() {
  const subject = state.lang === "es"
    ? "Solicitud de información — Demo Nébula Sur"
    : "Info request — Nébula Sur demo";

  const body = `${state.i18n.proposalIntro}\n\n${cartSummaryText()}`;
  return `mailto:${CONTACT.email}?subject=${encodeURIComponent(subject)}&body=${encodeURIComponent(body)}`;
}

function updateContactLinks() {
  refs.whatsappBtn.href = buildWhatsappLink(false);
  refs.emailBtn.href = buildMailtoLink();
  refs.phoneBtn.href = `tel:${CONTACT.phone}`;
}

function getKbName(kb) {
  if (!state.i18n) return kb;
  if (kb === "B") return state.i18n.kbNameB || "B";
  if (kb === "C") return state.i18n.kbNameC || "C";
  return state.i18n.kbNameA || "A";
}

function getAgentName(kb) {
  if (kb === "B") return state.lang === "es" ? "Diego Martín" : "Diego Martin";
  if (kb === "C") return state.lang === "es" ? "Marta Velasco" : "Marta Velasco";
  return state.lang === "es" ? "Laura Serrano" : "Laura Serrano";
}

function buildWelcomeMessage() {
  const template = state.i18n.welcomeTemplate || "";
  return template
    .replace("{agentName}", getAgentName(state.kb))
    .replace("{kbName}", getKbName(state.kb));
}

function appendMessage(role, text) {
  const node = document.createElement("div");
  node.className = `msg ${role === "user" ? "user" : role === "meta" ? "meta" : "bot"}`;
  node.textContent = text;
  refs.chatMessages.appendChild(node);
  refs.chatMessages.scrollTop = refs.chatMessages.scrollHeight;
}

function isMobile() {
  return window.matchMedia("(max-width: 767px)").matches;
}
