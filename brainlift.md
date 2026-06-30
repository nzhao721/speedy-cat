# BrainLift: MCAT Test Prep

## DOK 4: Spiky Points of View (SPOVs)

### SPOV 1: Guide the student through studying.

Having students self-direct when they choose to seek feedback or not and only having static all-or-none feedback options (reading the solution in the back of the book/flashcard) is inefficient and demotivating

### SPOV 2: Everything that a student needs to score well on the MCAT is already on the internet.

It merely needs to be integrated into one frictionless platform so 100% of students' time and cognitive load can go towards studying

---

## Experts

### Kenneth R. Koedinger & Vincent Aleven
- **Who:** Professors at Carnegie Mellon University's Human-Computer Interaction Institute and creators of Cognitive Tutors.
- **Focus:** Intelligent tutoring systems and the "assistance dilemma," the question of how much help to give a learner and when.
- **Why Follow:** Their research is the foundation for this BrainLift's stance that hints must be well-timed and well-scoped, and that the system, rather than the student, should decide when assistance appears.
- **Where:** https://scholar.google.com/scholar?q=Koedinger+Aleven+assistance+dilemma

### Henry L. Roediger III & Jeffrey D. Karpicke
- **Who:** Roediger is a memory researcher at Washington University in St. Louis; Karpicke directs the Cognition and Learning Laboratory at Purdue University.
- **Focus:** The testing effect (retrieval practice) and which study techniques most reliably produce durable learning.
- **Why Follow:** Their work establishes that practice testing (active recall) and distributed practice are among the highest-utility study techniques, grounding this BrainLift's emphasis on practice questions and spaced repetition.
- **Where:** http://psychnet.wustl.edu/memory/ · https://www.purdue.edu/learninglab/

### Paul A. Kirschner, Carl Hendrick & Jim Heal
- **Who:** Authors of *Instructional Illusions*; Kirschner is an emeritus professor of educational psychology, and Hendrick and Heal are researchers and writers who translate learning science into classroom practice.
- **Focus:** The gap between immediate classroom performance and long-term transfer, the role of motivation and early success, cognitive load, and evidence-based instruction.
- **Why Follow:** Their analyses of the "transfer illusion" and "motivation illusion" directly inform this BrainLift's views on teaching for transfer through contextual variation, building motivation through early success, and the careful timing of feedback.
- **Where:** https://www.hachettelearning.com/teaching-strategies/instructional-illusions · https://carlhendrick.substack.com/

---

## DOK 3: Insights

### On well-timed, well-scoped feedback
- **Insight 1:** With current front-running tools, there is no systematic control of how much and when assistance is provided to students while they complete practice questions and study
- **Insight 2:** Well-timed feedback is a key piece of solving the problem of motivation and continued motivation; intrinsic motivation results from a sense of independent accomplishment and the reward of hard work paying off, and well-timed confirming feedback that keeps the user doing achievable tasks with an appropriate delay in feedback
- **How I plan to implement this:** My application will have an AI feature where the user can show their work in realtime and "think out loud" with a chatbot. The chatbot will give feedback at appropriate times (waiting for a threshold of a long enough time where the student hasn't made substantial progress)

### On what our target users NEED the most
- **Insight 1:** The "holy trinity" of apps, plus many other popular tools, already deliver the educational content in a highly effective way, backed by learning science
- **Insight 2:** Using a combination of these tools is highly effective in terms of learning the material, but these tools are not always accessible or easy to use, and are scattered across multiple platforms provided by profit-seeking companies and cost thousands of dollars and hundreds of hours
- **Insight 3:** The friction caused by the difficulty of finding resources such as lessons, practice questions, and full-length practice tests scattered across the internet leads to an unnecessary increase in cognitive load and decreases motivation, engagement, and performance
- **How I plan to build an app that the user needs:** I will integrate all of the available resources and streamline the learning experience into one catch-all platform so users do not have to spend thousands of dollars and hours trying out different test prep sites and hunting for practice material

---

## DOK 2: Knowledge Tree

### 1. The MCAT and the Test-Prep Landscape

**Source: Association of American Medical Colleges (AAMC). MCAT exam skills and format.**
- **DOK 1 - Facts:**
  - Knowledge of scientific concepts and principles.
  - Scientific reasoning and problem-solving.
  - Reasoning about the design and execution of research.
  - Data-based and statistical reasoning.
  - 4 sections of multiple-choice questions, total 6h 15m testing time:
    - Chemical and Physical Foundations of Biological Systems
    - Critical Analysis and Reasoning Skills
    - Biological and Biochemical Foundations of Living Systems
    - Psychological, Social, and Biological Foundations of Behavior
- **DOK 2 - Summary:** the MCAT tests a vast knowledge base as well as the skills to use that knowledge to conduct reasoning and solve problems based on never-before-seen information

**Source: American Medical Association. *What is Anki?***
- **DOK 1 - Facts:**
  - What Anki does:
    - Primarily helps with memory by using spaced repetition
    - Uses active recall, where there is a prompt that asks students to type in flashcard content before they can flip the flashcard to check their answers
    - Students can use pre-built sets of flashcards, create their own, and share with others
- **DOK 2 - Summary:** Anki is a flashcard app that helps students study for graduate-level exams primarily by facilitating memory of simple facts
- **Link to source:** https://www.ama-assn.org/medical-students/succeed-medical-school/what-anki

**Source: *Converting 3rd-party MCAT scores to actual scores* (joel.vg).**
- **DOK 1 - Facts:**
  - Only the official AAMC practice tests (of which there are only three publicly available) are an accurate reflection of the real projected score
  - Practice created by other companies is all deflated, Kaplan by about 10 points, NextStep by about 7, Princeton Review by about 15 points
  - This protects their money-back guarantee, which rests on you scoring higher than your practice
- **DOK 2 - Summary:** Practice tests are the most valuable and reliable way of estimating real scores, but commercial interests make the availability of accurate practice tests very low
- **Link to source:** https://joel.vg/converting-3rd-party-mcat-scores-to-actual-scores/

**Source: Synthesized from multiple sources (AI-assisted, from many social sources and the internet).**
- **DOK 2 - Facts:**
  - The "holy trinity" of test prep for MCAT: Anki flashcards, UWorld practice, AAMC full-length practice tests
  - These are very expensive and rare: prices for their test prep plans can easily be several thousand dollars
  - Full-length practice tests are hard to find; only three real ones are available and the other ones by companies have deflation for business interests
  - per AAMC's own data, the average examinee studies roughly 20 hours/week for 3 months (~240 hours total).

### 2. The Science of Learning and Studying

**Source: Kirschner, P. A., Hendrick, C., & Heal, J. (2025). *Instructional Illusions* (pp. 37–43). Hachette Learning. (The transfer illusion)**
- **DOK 1 - Facts:**
  - Immediate performance in the classroom doesn't translate to application of skills in practice: for example, a baker who can exactly replicate a recipe in front of their teacher can't do the same at home with slightly different temperature, flour, etc.
  - Problem solving is a bad way for novices to learn how to solve problems
  - Tradeoff between immediate performance and long-term application: the controlled learning environments like a classroom that optimize rapid skill acquisition poorly prepare students for application in exams
  - Ways to solve: involve contextual variation; for example, medical students train consistent pathophysiological principles while varying patient specifics like demographics, presenting symptoms, and clinical setting
- **DOK 2 - Summary:** Students must be taught in a way that allows them to apply skills in a wide variety of contexts, achieved through giving them problems in various contexts for the same concept.

**Source: Kirschner, P. A., Hendrick, C., & Heal, J. (2025). *Instructional Illusions* (pp. 51–57). Hachette Learning. (The motivation illusion)**
- **DOK 1 - Facts:**
  - Many people think that learning will result from motivation; it is actually the other way around; students who experience early success will be more motivated to continue learning
  - To achieve this effect, break tasks into small steps for learners to experience early successes
  - 80% success strikes balance between confidence and complacency
  - Feedback should maintain a delicate balance: focused enough to guide, not too focused to circumvent productive struggle
  - Timing of actionable feedback is crucial to maintaining delicate balance between motivation and effective learning
- **DOK 2 - Summary:** Students must be motivated by allowing them early successes, and guided through the learning process with well-timed hints to achieve motivation with optimal struggle.

**Source: Koedinger, K. R., & Aleven, V. (2007). Exploring the Assistance Dilemma in Experiments with Cognitive Tutors. *Educational Psychology Review*, 19(3), 239–264.**
- **DOK 1 - Facts:**
  - There are benefits and drawbacks to both too much and too little assistance
  - Low assistance forces effortful attention and retains intrinsic motivation and reward for independent success, while risking demotivation from failure and wasted time spent completely off track
  - High assistance ensures accuracy and improves the efficiency of successful completion, but risks shallow processing and lower engagement
  - Cognitive Tutors: provide a rich problem-solving environment where students can access step-by-step feedback, targeted selection of problems
  - Feedback with explanatory content is better than yes or no feedback
  - Students are not good at seeking assistance or information at the right time
- **DOK 2 - Summary:** A hint must be well-timed and give just enough information to the student. The program, and not the student, should choose the timing of hints.
- **Link to source:** https://pslcdatashop.web.cmu.edu/KDDCup/FAQ/Koedinger-Aleven-EPR-07.pdf

**Source: Roediger, H. L. & Karpicke, J. D. (2006). Test enhanced learning: Taking memory tests improves long term retention. *Psychological Science*, 17(3), 249–255.**
- **DOK 1 - Facts:**
  - Practice testing and distributed practice received high utility assessments because they benefit learners of different ages and abilities and have been shown to boost students' performance across many criterion tasks and even in educational contexts
  - Elaborative interrogation, self-explanation, and interleaved practice received moderate utility assessments.
  - Five techniques received a low utility assessment: summarization, highlighting, the keyword mnemonic, imagery use for text learning, and rereading
- **DOK 2 - Summary:** doing practice tests (active recall) and spaced repetition are proven to be highly effective and interleaving of concepts and elaboration are also seen as somewhat effective techniques, with other techniques being proven ineffective

**Source: Ouwehand, K., Lespiau, F., Tricot, A., & Paas, F. (2025). Cognitive Load Theory: Emerging Trends and Innovations. *Education Sciences*, 15(4), 458.**
- **DOK 1 - Facts:**
  - Cognitive load theory: part of it acknowledges that cognitive load is higher when critical information is spread out across multiple sources
- **DOK 2 - Summary:** Information that is too spread out makes unnecessarily high cognitive load
- **Link to source:** https://doi.org/10.3390/educsci15040458

**Source: Evans, P., Vansteenkiste, M., Parker, P. et al. (2024). Cognitive Load Theory and Its Relationships with Motivation: a Self-Determination Theory Perspective. *Educational Psychology Review*, 36, 7.**
- **DOK 1 - Facts:**
  - Teacher efforts to use teaching methods that lower cognitive load were mostly effective at reducing cognitive load
  - Lower cognitive load lead to better performance and increased motivation and engagement
- **DOK 2 - Summary:** Implementing methods that try to reduce cognitive load for students yields better motivation and engagement and better academic performance
- **Link to source:** https://doi.org/10.1007/s10648-023-09841-2
