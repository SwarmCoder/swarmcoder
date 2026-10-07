# Bookshelf — a personal reading tracker

I want a small web app for keeping track of the books I own and what I think of
them. Nothing fancy — this is for one person, me, not a library system.

## What it needs to do

**Keep a list of my books.** For each book I want to record its title, its
author, the year it was published, and where I am with it — haven't started,
currently reading, or finished.

**Let me add a book, change it, and remove it.** I should be able to type in a
new book, fix a typo later, and take a book off the list if I got rid of it or
added it by mistake.

**Let me find a book again.** As the list grows I need to search by title or
author, and narrow it down to just the ones I'm currently reading, or just the
ones I've finished.

**Let me say what I thought of a book.** Once I've read something I want to give
it a rating so I can remember which ones were actually good.

**Remember everything between visits.** If I close the browser and come back
tomorrow, my books and my ratings should still be there.

## What it should feel like

It should look and behave like one coherent app, not a form somebody bolted
together — proper fields with labels, not placeholder text standing in for a
label, and it should tell me clearly if I've typed something that doesn't make
sense (a made-up year, a rating that isn't a real rating) rather than just
failing silently or letting bad data in.

## What I don't need

No sharing, no accounts, no recommendations, no importing from anywhere else.
Just my own list, kept straight.
