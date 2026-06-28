// LLMjs composable vision examples.
// Copy into kubejs/server_scripts/ and reload scripts.
//
// Commands:
//   !ui        capture current client frame -> multimodal LLM -> actionbar
//   !uiharness capture current client frame -> multimodal LLM -> text harness -> actionbar
//   !photo     held Exposure photograph -> multimodal LLM -> actionbar

const VISION_PROMPT = [
  'Inspect the current Minecraft screenshot, including any open UI.',
  'Return one concise actionable hint for the player.',
  'If nothing important is visible, say "No urgent UI issue."'
].join('\n')

function showImageAnswer(player, image, prompt) {
  LLM.chatImage(prompt, image, {
    provider: 'openai',
    system: 'You are a Minecraft UI assistant. Be precise and brief.',
    maxTokens: 80
  }, result => {
    if (result.success) LLM.actionbar(player, result.content)
    else LLM.actionbar(player, 'Vision failed: ' + result.error)
  })
}

function showImageAnswerWithHarness(player, image, prompt) {
  LLM.chatImage(prompt, image, {
    provider: 'openai',
    maxTokens: 80
  }, result => {
    if (!result.success) {
      LLM.actionbar(player, 'Vision failed: ' + result.error)
      return
    }

    LLM.chat([
      'Reply only PASS or FAIL.',
      'PASS if this answer identifies a concrete problem, warning, danger, blocked UI state, or missing requirement.',
      'FAIL if it is vague or only says OK.',
      '',
      'Candidate answer:',
      result.content
    ].join('\n'), { provider: 'openai', temperature: 0, maxTokens: 8 }, judge => {
      if (judge.success && String(judge.content).trim().startsWith('PASS')) {
        LLM.actionbar(player, result.content)
      }
    })
  })
}

PlayerEvents.chat(event => {
  const msg = event.message.trim()
  const player = event.player

  if (msg === '!ui') {
    event.cancel()
    LLM.captureScreenshot(player, {
      compression: 'auto',
      mimeType: 'image/jpeg',
      maxWidth: 768,
      maxBytes: 420000,
      quality: 0.78,
      detail: 'low'
    }, image => {
      showImageAnswer(player, image, VISION_PROMPT)
    }, error => {
      LLM.actionbar(player, 'Screenshot failed: ' + error)
    })
  }

  if (msg === '!uiharness') {
    event.cancel()
    LLM.captureScreenshot(player, {
      compression: 'auto',
      maxWidth: 900,
      maxBytes: 500000,
      quality: 0.75,
      detail: 'low'
    }, image => {
      showImageAnswerWithHarness(player, image, [
        'Look for a clear warning, error, danger, blocked button, missing item,',
        'or other actionable problem in the current Minecraft UI/screen.',
        'If present, answer with the problem only. If absent, answer "OK".'
      ].join('\n'))
    })
  }

  if (msg === '!photo') {
    event.cancel()
    const image = LLM.getExposurePhoto(player, { maxBytes: 420000, detail: 'low' })
    if (image == null) {
      LLM.actionbar(player, 'Hold an Exposure photograph first')
      return
    }
    showImageAnswer(player, image, [
      'Inspect this Exposure photograph from the Minecraft world.',
      'Describe the most important visible subject or clue in under 60 characters.'
    ].join('\n'))
  }
})
