import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../l10n/app_strings.dart';
import '../../models/app_models.dart';
import '../../platform/livebridge_platform.dart';
import '../../theme/livebridge_tokens.dart';
import '../../utils/livebridge_haptics.dart';
import '../../widgets/redesign/lb_detail_screen.dart';
import '../../widgets/redesign/lb_icon.dart';
import '../../widgets/redesign/lb_list_component.dart';
import '../../widgets/redesign/lb_toast.dart';

const String _adbAllowListenerCommand =
    'adb shell cmd notification allow_listener com.appsfolder.livebridge/.liveupdate.LiveUpdateNotificationListenerService';

class SettingsPermissionsScreen extends StatefulWidget {
  const SettingsPermissionsScreen({super.key});

  @override
  State<SettingsPermissionsScreen> createState() =>
      _SettingsPermissionsScreenState();
}

class _SettingsPermissionsScreenState extends State<SettingsPermissionsScreen>
    with WidgetsBindingObserver {
  bool _listenerEnabled = false;
  bool _notificationsGranted = false;
  bool _canPostPromoted = false;
  bool _hidePromotedAccess = false;
  int _androidSdkInt = 0;
  bool _overlayGranted = false;
  bool _capsulePrefEnabled = true;

  bool get _liveUpdatesUnavailableOnOs =>
      _androidSdkInt > 0 &&
      _androidSdkInt < DeviceInfo.liveUpdatesMinimumSdkInt;

  bool get _capsuleRowVisible =>
      _androidSdkInt == 0 ||
      _androidSdkInt < DeviceInfo.liveUpdatesMinimumSdkInt;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      unawaited(_loadState());
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      unawaited(_loadState());
    }
  }

  void _snack(String value) {
    if (!mounted) {
      return;
    }
    showLbToast(context, message: value);
  }

  Future<void> _loadState() async {
    try {
      final bool listenerEnabled =
          await LiveBridgePlatform.isNotificationListenerEnabled();
      final bool notificationsGranted =
          await LiveBridgePlatform.isNotificationPermissionGranted();
      final bool canPostPromoted =
          await LiveBridgePlatform.canPostPromotedNotifications();
      final bool overlayGranted = await LiveBridgePlatform.hasOverlayPermission();
      final bool capsulePrefEnabled =
          await LiveBridgePlatform.getCapsuleOverlayEnabled();
      final DeviceInfo deviceInfo = await LiveBridgePlatform.getDeviceInfo();

      if (!mounted) {
        return;
      }

      setState(() {
        _listenerEnabled = listenerEnabled;
        _notificationsGranted = notificationsGranted;
        _canPostPromoted = canPostPromoted;
        _hidePromotedAccess = deviceInfo.shouldHideLiveUpdatesPromotion;
        _androidSdkInt = deviceInfo.sdkInt;
        _overlayGranted = overlayGranted;
        _capsulePrefEnabled = capsulePrefEnabled;
      });
    } catch (_) {}
  }

  Future<void> _requestNotificationPermission() async {
    unawaited(LiveBridgeHaptics.confirm());
    final bool granted =
        await LiveBridgePlatform.requestNotificationPermission();
    if (!mounted) {
      return;
    }
    final AppStrings strings = AppStrings.of(context);
    _snack(granted ? strings.permissionGranted : strings.permissionDenied);
    await _loadState();
  }

  Future<void> _openListenerSettings() async {
    unawaited(LiveBridgeHaptics.openSurface());
    final bool opened =
        await LiveBridgePlatform.openNotificationListenerSettings();
    if (!mounted || opened) {
      return;
    }
    _snack(AppStrings.of(context).listenerUnavailable);
  }

  Future<void> _openAppNotificationSettings() async {
    unawaited(LiveBridgeHaptics.openSurface());
    final bool opened = await LiveBridgePlatform.openAppNotificationSettings();
    if (!mounted || opened) {
      return;
    }
    _snack(AppStrings.of(context).notificationsUnavailable);
  }

  Future<void> _showAdbHelpSheet() async {
    final AppStrings strings = AppStrings.of(context);
    final LbPalette palette = LbPalette.of(context);
    showDialog<void>(
      context: context,
      builder: (BuildContext dialogContext) {
        return AlertDialog(
          backgroundColor: palette.surface,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(24),
          ),
          title: Text(
            strings.adbHelpTitle,
            style: LbTextStyles.cardTitle.copyWith(
              color: palette.textPrimary,
            ),
          ),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: palette.surfaceSoft,
                  borderRadius: BorderRadius.circular(12),
                ),
                child: SelectableText(
                  _adbAllowListenerCommand,
                  style: const TextStyle(
                    fontFamily: 'monospace',
                    fontSize: 12,
                    height: 1.4,
                    color: Color(0xFFCBB4FF),
                  ),
                ),
              ),
            ],
          ),
          actions: <Widget>[
            TextButton(
              onPressed: () {
                Clipboard.setData(
                  const ClipboardData(text: _adbAllowListenerCommand),
                );
                Navigator.of(dialogContext).pop();
                _snack(strings.adbCopied);
              },
              child: Text(strings.copyAction),
            ),
          ],
        );
      },
    );
  }

  Future<void> _setCapsuleEnabled(bool value) async {
    if (value) {
      if (!_overlayGranted) {
        unawaited(LiveBridgeHaptics.openSurface());
        final bool opened = await LiveBridgePlatform.openOverlaySettings();
        if (!mounted || opened) {
          return;
        }
        _snack(AppStrings.of(context).overlayUnavailable);
        return;
      }
      await LiveBridgePlatform.setCapsuleOverlayEnabled(true);
    } else {
      await LiveBridgePlatform.setCapsuleOverlayEnabled(false);
    }
    await _loadState();
  }

  Future<void> _openPromotedSettings() async {
    unawaited(LiveBridgeHaptics.openSurface());
    if (_liveUpdatesUnavailableOnOs) {
      _snack(AppStrings.of(context).liveUpdatesOsUnavailable);
      return;
    }
    final bool opened =
        await LiveBridgePlatform.openPromotedNotificationSettings();
    if (!mounted || opened) {
      return;
    }
    _snack(AppStrings.of(context).liveUpdatesUnavailable);
  }

  LbListItemData _buildPermissionItem({
    required String title,
    required bool enabled,
    required VoidCallback onTap,
    bool unavailable = false,
    String? description,
  }) {
    return LbListItemData(
      title: title,
      description: description,
      trailingIcon: enabled
          ? null
          : (unavailable ? LbIconSymbol.info : LbIconSymbol.alertOctagonFilled),
      trailingIconColor: enabled
          ? null
          : (unavailable
              ? LbPalette.of(context).textSecondary
              : LbPalette.of(context).warning),
      onTap: onTap,
    );
  }

  @override
  Widget build(BuildContext context) {
    final AppStrings strings = AppStrings.of(context);

    final List<LbListItemData> permissionItems = <LbListItemData>[
      _buildPermissionItem(
        title: strings.listenerAccess,
        enabled: _listenerEnabled,
        description:
            _listenerEnabled ? null : strings.listenerRestrictedHint,
        onTap: () {
          unawaited(_openListenerSettings());
        },
      ),
      _buildPermissionItem(
        title: strings.postNotifications,
        enabled: _notificationsGranted,
        onTap: () {
          if (_notificationsGranted) {
            unawaited(_openAppNotificationSettings());
          } else {
            unawaited(_requestNotificationPermission());
          }
        },
      ),
      if (_capsuleRowVisible)
        LbListItemData(
          title: strings.capsuleOverlay,
          description: strings.capsuleOverlayDescription,
          toggleValue: _overlayGranted && _capsulePrefEnabled,
          onToggle: (bool value) {
            unawaited(_setCapsuleEnabled(value));
          },
          showChevron: false,
        ),
      if (!_hidePromotedAccess)
        _buildPermissionItem(
          title: strings.liveUpdatesAccess,
          enabled: _canPostPromoted,
          unavailable: _liveUpdatesUnavailableOnOs,
          onTap: () {
            unawaited(_openPromotedSettings());
          },
        ),
      LbListItemData(
        title: strings.adbHelpTitle,
        description: strings.adbHelpDescription,
        onTap: () {
          unawaited(LiveBridgeHaptics.selection());
          unawaited(_showAdbHelpSheet());
        },
      ),
    ];

    return LbDetailScreen(
      title: strings.accessTitle,
      children: <Widget>[
        LbListComponent(
          items: permissionItems,
          rowHeight: LbSpacing.recentRowHeight,
          extendDividersToEnd: true,
        ),
      ],
    );
  }
}
