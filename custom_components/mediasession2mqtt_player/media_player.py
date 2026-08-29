"""Home Assistant media player backed directly by MediaSession2MQTT MQTT topics."""

from __future__ import annotations

from datetime import datetime
import hashlib
from typing import Any

import voluptuous as vol

from homeassistant.components import mqtt
from homeassistant.components.media_player import (
    MediaPlayerDeviceClass,
    MediaPlayerEntity,
    MediaPlayerEntityFeature,
    MediaPlayerState,
    PLATFORM_SCHEMA as MEDIA_PLAYER_PLATFORM_SCHEMA,
)
from homeassistant.const import CONF_NAME
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers import config_validation as cv
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.helpers.typing import ConfigType, DiscoveryInfoType
from homeassistant.util import dt as dt_util

CONF_DEVICE_ID = "device_id"
DEFAULT_NAME = "Android Media"
MAX_ARTWORK_BYTES = 2 * 1024 * 1024

# android.media.session.PlaybackState action flags.
ACTION_STOP = 1
ACTION_PAUSE = 2
ACTION_PLAY = 4
ACTION_SKIP_TO_PREVIOUS = 16
ACTION_SKIP_TO_NEXT = 32
ACTION_SEEK_TO = 256
ACTION_PLAY_PAUSE = 512

PLATFORM_SCHEMA = MEDIA_PLAYER_PLATFORM_SCHEMA.extend(
    {
        vol.Optional(CONF_NAME, default=DEFAULT_NAME): cv.string,
        vol.Required(CONF_DEVICE_ID, default=1): cv.positive_int,
    }
)


async def async_setup_platform(
    hass: HomeAssistant,
    config: ConfigType,
    async_add_entities: AddEntitiesCallback,
    discovery_info: DiscoveryInfoType | None = None,
) -> None:
    """Set up a MediaSession2MQTT-backed media player."""
    async_add_entities([MediaSession2MQTTPlayer(hass, config)])


class MediaSession2MQTTPlayer(MediaPlayerEntity):
    """Expose MediaSession2MQTT as a native Home Assistant media player."""

    _attr_should_poll = False
    _attr_device_class = MediaPlayerDeviceClass.SPEAKER

    def __init__(self, hass: HomeAssistant, config: ConfigType) -> None:
        self.hass = hass
        self._device_id = config[CONF_DEVICE_ID]
        self._root_topic = f"mediaSession/{self._device_id}"
        self._attr_name = config[CONF_NAME]
        self._attr_unique_id = f"mediasession2mqtt_{self._device_id}_player"

        self._playback_state: str | None = None
        self._playback_position: float | None = None
        self._playback_position_updated_at: datetime | None = None
        self._playback_actions = 0
        self._application_id: str | None = None
        self._media_title: str | None = None
        self._media_duration: float | None = None
        self._volume_level: float | None = None
        self._volume_control: str | None = None
        self._volume_muted: bool | None = None
        self._media_control_enabled = False
        self._artwork_bytes: bytes | None = None
        self._artwork_hash: str | None = None

    async def async_added_to_hass(self) -> None:
        """Subscribe directly to MediaSession2MQTT state topics."""
        await super().async_added_to_hass()
        text_topics = {
            "playbackState": self._set_playback_state,
            "playbackPosition": self._set_playback_position,
            "playbackActions": self._set_playback_actions,
            "applicationId": self._set_application_id,
            "mediaTitle": self._set_media_title,
            "mediaDuration": self._set_media_duration,
            "volumeLevel": self._set_volume_level,
            "volumeControl": self._set_volume_control,
            "volumeMuted": self._set_volume_muted,
            "mediaControlEnabled": self._set_media_control_enabled,
        }
        for subtopic, handler in text_topics.items():
            self.async_on_remove(
                await mqtt.async_subscribe(
                    self.hass,
                    f"{self._root_topic}/{subtopic}",
                    handler,
                    qos=0,
                )
            )

        self.async_on_remove(
            await mqtt.async_subscribe(
                self.hass,
                f"{self._root_topic}/mediaArtwork",
                self._set_artwork,
                qos=0,
                encoding=None,
            )
        )

    @staticmethod
    def _payload(msg: Any) -> str:
        return str(msg.payload).strip()

    @callback
    def _set_playback_state(self, msg: Any) -> None:
        self._playback_state = self._payload(msg) or None
        self.async_write_ha_state()

    @callback
    def _set_playback_position(self, msg: Any) -> None:
        value = self._payload(msg)
        try:
            self._playback_position = float(value) / 1000.0 if value else None
        except ValueError:
            self._playback_position = None
        self._playback_position_updated_at = dt_util.utcnow()
        self.async_write_ha_state()

    @callback
    def _set_playback_actions(self, msg: Any) -> None:
        try:
            self._playback_actions = int(self._payload(msg))
        except ValueError:
            self._playback_actions = 0
        self.async_write_ha_state()

    @callback
    def _set_application_id(self, msg: Any) -> None:
        self._application_id = self._payload(msg) or None
        self.async_write_ha_state()

    @callback
    def _set_media_title(self, msg: Any) -> None:
        self._media_title = self._payload(msg) or None
        self.async_write_ha_state()

    @callback
    def _set_media_duration(self, msg: Any) -> None:
        value = self._payload(msg)
        try:
            self._media_duration = float(value) / 1000.0 if value else None
        except ValueError:
            self._media_duration = None
        self.async_write_ha_state()

    @callback
    def _set_volume_level(self, msg: Any) -> None:
        value = self._payload(msg)
        try:
            self._volume_level = max(0.0, min(1.0, float(value))) if value else None
        except ValueError:
            self._volume_level = None
        self.async_write_ha_state()

    @callback
    def _set_volume_control(self, msg: Any) -> None:
        self._volume_control = self._payload(msg) or None
        self.async_write_ha_state()

    @callback
    def _set_volume_muted(self, msg: Any) -> None:
        value = self._payload(msg).lower()
        self._volume_muted = value == "true" if value in ("true", "false") else None
        self.async_write_ha_state()

    @callback
    def _set_media_control_enabled(self, msg: Any) -> None:
        self._media_control_enabled = self._payload(msg).lower() == "true"
        self.async_write_ha_state()

    @callback
    def _set_artwork(self, msg: Any) -> None:
        payload = bytes(msg.payload)
        if not payload or len(payload) > MAX_ARTWORK_BYTES:
            self._artwork_bytes = None
            self._artwork_hash = None
        else:
            self._artwork_bytes = payload
            self._artwork_hash = hashlib.sha256(payload).hexdigest()[:16]
        self.async_write_ha_state()

    @property
    def supported_features(self) -> MediaPlayerEntityFeature:
        if not self._media_control_enabled:
            return MediaPlayerEntityFeature(0)
        features = MediaPlayerEntityFeature(0)
        actions = self._playback_actions
        if actions & (ACTION_PLAY | ACTION_PLAY_PAUSE):
            features |= MediaPlayerEntityFeature.PLAY
        if actions & (ACTION_PAUSE | ACTION_PLAY_PAUSE):
            features |= MediaPlayerEntityFeature.PAUSE
        if actions & ACTION_STOP:
            features |= MediaPlayerEntityFeature.STOP
        if actions & ACTION_SKIP_TO_NEXT:
            features |= MediaPlayerEntityFeature.NEXT_TRACK
        if actions & ACTION_SKIP_TO_PREVIOUS:
            features |= MediaPlayerEntityFeature.PREVIOUS_TRACK
        if actions & ACTION_SEEK_TO:
            features |= MediaPlayerEntityFeature.SEEK

        if self._volume_control in ("relative", "absolute"):
            features |= MediaPlayerEntityFeature.VOLUME_STEP | MediaPlayerEntityFeature.VOLUME_MUTE
        if self._volume_control == "absolute":
            features |= MediaPlayerEntityFeature.VOLUME_SET
        return features

    @property
    def state(self) -> MediaPlayerState | None:
        return {
            "playing": MediaPlayerState.PLAYING,
            "paused": MediaPlayerState.PAUSED,
            "idle": MediaPlayerState.IDLE,
            "off": MediaPlayerState.OFF,
        }.get(self._playback_state or "")

    @property
    def media_title(self) -> str | None:
        return self._media_title

    @property
    def media_duration(self) -> float | None:
        return self._media_duration

    @property
    def media_position(self) -> float | None:
        return self._playback_position

    @property
    def media_position_updated_at(self) -> datetime | None:
        return self._playback_position_updated_at

    @property
    def app_id(self) -> str | None:
        return self._application_id

    @property
    def app_name(self) -> str | None:
        return {
            "ru.yandex.music": "Yandex Music",
            "com.google.android.youtube": "YouTube",
            "com.google.android.youtube.tv": "YouTube",
        }.get(self._application_id or "", self._application_id)

    @property
    def source(self) -> str | None:
        return self.app_name

    @property
    def volume_level(self) -> float | None:
        return self._volume_level

    @property
    def is_volume_muted(self) -> bool | None:
        return self._volume_muted

    @property
    def media_image_url(self) -> str | None:
        return f"mediasession2mqtt://{self._artwork_hash}" if self._artwork_hash else None

    @property
    def media_image_hash(self) -> str | None:
        return self._artwork_hash

    async def async_get_media_image(self) -> tuple[bytes | None, str | None]:
        if self._artwork_bytes is None:
            return None, None
        return self._artwork_bytes, "image/jpeg"

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        return {
            "device_id": self._device_id,
            "playback_actions": self._playback_actions,
            "volume_control": self._volume_control,
            "media_control_enabled": self._media_control_enabled,
        }

    async def _publish_command(self, command: str, payload: str = "1") -> None:
        await mqtt.async_publish(
            self.hass,
            f"{self._root_topic}/command/{command}",
            payload,
            qos=0,
            retain=False,
        )

    async def async_media_play(self) -> None:
        await self._publish_command("play")

    async def async_media_pause(self) -> None:
        await self._publish_command("pause")

    async def async_media_stop(self) -> None:
        await self._publish_command("stop")

    async def async_media_next_track(self) -> None:
        await self._publish_command("next")

    async def async_media_previous_track(self) -> None:
        await self._publish_command("previous")

    async def async_media_seek(self, position: float) -> None:
        await self._publish_command("seek", str(max(0, round(position * 1000))))

    async def async_set_volume_level(self, volume: float) -> None:
        await self._publish_command("volume", str(max(0.0, min(1.0, volume))))

    async def async_volume_up(self) -> None:
        await self._publish_command("volumeUp")

    async def async_volume_down(self) -> None:
        await self._publish_command("volumeDown")

    async def async_mute_volume(self, mute: bool) -> None:
        await self._publish_command("mute" if mute else "unmute")
