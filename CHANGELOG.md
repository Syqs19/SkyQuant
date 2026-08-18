# SkyQuant - Change Log

Ready to paste into a Modrinth release. One line per change, written for the
person playing the game: say what is different now, not how it was built. No
class names, no refactors. Keep the reasoning in the commit message.

Groups: **New Features** (something that wasn't there), **Improvements** (something
that got better), **Fixes** (something that was wrong), **Technical Details** (no
visible effect - keep it to a line).

Oldest version first, newest at the bottom.

## Version 0.1.0

### New Features

+ Added the market terminal, opened with `B`, or `/sq` if you prefer typing.
+ Added the Watchlist tab for the items you are keeping an eye on.
+ Added the Flip tab, ranking the bazaar by margin.
+ Added the NPC to Bazaar tab, for shop items worth reselling.
+ Added the Craft tab, showing whether an item is worth more made than bought.
+ Added the Forge tab, ranked by profit per hour rather than profit.
+ Added a price graph for any item, opened with `G` on whatever you are pointing at.
+ Added a movable HUD, arranged in its own editor.
+ Every profit figure is net of bazaar tax, read from your own Bazaar Flipper rate.

## Version 0.1.1

### New Features

+ Added the Status tab, showing what you have working right now and how many coins are tied up in it.
+ Added forge timers that keep running after you leave the forge island.
+ Added a Filters button to the terminal, for putting away any figure you do not use on a tab.

### Improvements

+ The Forge tab now marks items that barely trade and sorts them last, instead of ranking them first.
+ The Depth column now counts every order near the best price, not just the cheapest one.
+ The LIVE indicator now shows how old the prices are instead of staying lit forever.
+ Tooltips now say what a number means rather than why the column is there.
+ Reworded the screens that described a menu by its name rather than by what it offers.

### Fixes

+ Fixed watched items showing "no auction data" when the mod was only pacing its own requests.
+ Fixed HUD positions and watchlist entries being lost after a crash or Alt+F4.
+ Fixed the Forge tab ranking on the asking price of items nobody was buying.
+ Fixed the in-game help still naming a command that no longer exists.

### Technical Details

+ The terminal no longer re-sorts every recipe sixty times a second, freeing about a third of each frame.
