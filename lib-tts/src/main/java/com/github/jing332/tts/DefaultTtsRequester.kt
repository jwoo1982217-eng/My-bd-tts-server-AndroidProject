package com.github.jing332.tts

import com.github.jing332.tts.error.RequesterError
import com.github.jing332.tts.synthesizer.ITtsRequester
import com.github.jing332.tts.synthesizer.SystemParams
import com.github.jing332.tts.synthesizer.TtsConfiguration
import com.github.jing332.tts.speech.EngineState
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.CancellationException

class DefaultTtsRequester(
    var context: SynthesizerContext,
) : ITtsRequester {
    override suspend fun request(
        params: SystemParams, tts: TtsConfiguration,
    ): Result<ITtsRequester.Response, RequesterError> {
        val engine =
            CachedEngineManager.getEngine(context.androidContext, tts.source) ?: return Err(
                RequesterError.StateError("engine ${tts.source} not found")
            )
        if (engine.state != EngineState.Initialized) {
            engine.onInit()
        }

        return if (engine.isSyncPlay(tts.source)) {
            Ok(
                ITtsRequester.Response(
                    callback = ITtsRequester.ISyncPlayCallback {
                        engine.syncPlay(params, tts.source)
                    }
                )
            )
        } else {
            try {
                Ok(
                    ITtsRequester.Response(stream = engine.getStream(params, tts.source))
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Err(RequesterError.RequestError(e))
            }

        }
    }

    override fun destroy() {
    }

}